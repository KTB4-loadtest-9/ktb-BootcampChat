const {
    createChatRoomAction,
    joinRandomChatRoomAction,
    sendMessageAction,
    sendMultipleMessagesAction,
    uploadFileAction,
} = require('../../actions/chat.actions');
const { bannedWordSafeText } = require('../../utils/bannedWordSafeText');
const { expect } = require('@playwright/test');
const path = require('path');
const { randomUUID } = require('crypto');

const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const MASS_MESSAGE_COUNT = process.env.MASS_MESSAGE_COUNT || 10;

// Action 간 timeout 설정 (환경변수로 조절 가능)
const ACTION_TIMEOUT = parseInt(process.env.ACTION_TIMEOUT || '1000', 10);
const ACTION_TIMEOUT_SHORT = parseInt(process.env.ACTION_TIMEOUT_SHORT || '500', 10);
const ACTION_TIMEOUT_LONG = parseInt(process.env.ACTION_TIMEOUT_LONG || '2000', 10);
const FILE_UPLOAD_RESPONSE_TIMEOUT = Math.max(ACTION_TIMEOUT_LONG * 3, 45000);

async function safeResponseBody(response) {
    try {
        const body = await response.text();
        return body
            .replace(/https?:\/\/[^\s"']+/g, '[redacted-url]')
            .replace(/Bearer\s+[^\s"']+/gi, 'Bearer [redacted]')
            .slice(0, 500);
    } catch {
        return '<response body unavailable>';
    }
}

function waitForResponseResult(page, predicate, timeout) {
    return page.waitForResponse(predicate, { timeout })
        .then(response => ({ response }))
        .catch(error => ({ error }));
}

async function gotoChatPage(page, vuContext) {
    await page.goto(`${BASE_URL}/chat`);
    await expect(page).toHaveURL(`${BASE_URL}/chat`);
}

/**
 * Artillery 채팅방 생성 및 메시지 전송 시나리오
 */
async function chatRoomCreationScenario(page, vuContext) {
    try {
        // 1. 채팅방 확인
        await expect(page).toHaveURL(`${BASE_URL}/chat`);

        // 2. 채팅방 생성
        const roomName = `부하테스트_${randomUUID()}`;
        await createChatRoomAction(page, roomName);
        await expect(page).toHaveURL(new RegExp(`${BASE_URL}/chat/\\w+`));

        // URL 이 바뀌어도 소켓이 안 붙으면 방은 로딩 스피너에 머문다.
        // 입력창이 떠야 방에 들어온 것이고, 여기서 끊어야 다음 단계가
        // "입력창을 못 찾음"으로 30s 를 태우지 않는다.
        await expect(page.getByTestId('chat-message-input')).toBeVisible();

        // 3. 메시지 전송
        const message = `테스트 메시지 ${bannedWordSafeText(Date.now())}`;
        await sendMessageAction(page, message);
        await page.waitForTimeout(ACTION_TIMEOUT_SHORT);

        const messageElement = page.getByTestId('message-content').filter({ hasText: message });
        await expect(messageElement).toBeVisible();

        vuContext.vars.chatRoomUrl = page.url();
    } catch (error) {
        console.error('Chat room creation scenario failed:', error.message);
        throw error;
    }
}

/**
 * Artillery 메시지 대량 전송 시나리오
 */
async function massMessageScenario(page, vuContext) {
    try {
        // 1. 랜덤 채팅방 입장
        await joinRandomChatRoomAction(page);
        await expect(page).toHaveURL(new RegExp(`${BASE_URL}/chat/\\w+`));

        // 2. 여러 메시지 연속 전송 (10개)
        console.log(`Sending ${MASS_MESSAGE_COUNT} messages...`);
        await sendMultipleMessagesAction(page, MASS_MESSAGE_COUNT);
    } catch (error) {
        console.error('Mass message scenario failed:', error.message);
        throw error;
    }
}

/**
 * Artillery 파일 업로드 시나리오
 */
async function fileUploadScenario(page, vuContext) {
    try {
        // 1. 랜덤 채팅방 입장
        await joinRandomChatRoomAction(page);
        await expect(page).toHaveURL(new RegExp(`${BASE_URL}/chat/\\w+`));

        // 2. 이미지 파일 업로드
        const filePath = path.resolve(__dirname, '../../fixtures/images/profile.jpg');
        const message = `파일 업로드 부하 테스트 ${bannedWordSafeText(Date.now())}`;

        const diagnostics = {
            presignResponse: null,
            s3PutResponse: null,
            s3PutFailure: null,
        };
        const onResponse = response => {
            if (response.url().includes('/api/files/chat-images/presign')) {
                diagnostics.presignResponse = response;
            } else if (response.request().method() === 'PUT') {
                diagnostics.s3PutResponse = response;
            }
        };
        const onRequestFailed = request => {
            if (request.method() === 'PUT') {
                diagnostics.s3PutFailure = request.failure()?.errorText || 'unknown network error';
            }
        };

        page.on('response', onResponse);
        page.on('requestfailed', onRequestFailed);

        try {
            // Do not filter by status here. A 4xx/5xx response is the most useful
            // diagnostic and must not be hidden behind a Playwright timeout.
            const uploadResultPromise = waitForResponseResult(
                page,
                response => response.url().includes('/api/files/upload'),
                FILE_UPLOAD_RESPONSE_TIMEOUT
            );

            await uploadFileAction(page, filePath, message);
            const uploadResult = await uploadResultPromise;

            if (uploadResult.error) {
                const presignStatus = diagnostics.presignResponse?.status() ?? 'not-observed';
                const presignBody = diagnostics.presignResponse && presignStatus >= 400
                    ? await safeResponseBody(diagnostics.presignResponse)
                    : 'n/a';
                const putStatus = diagnostics.s3PutResponse?.status() ?? 'not-observed';
                throw new Error(
                    `Upload completion response not observed within ${FILE_UPLOAD_RESPONSE_TIMEOUT}ms; ` +
                    `presignStatus=${presignStatus}; presignBody=${presignBody}; ` +
                    `s3PutStatus=${putStatus}; s3PutFailure=${diagnostics.s3PutFailure || 'none'}`
                );
            }

            const uploadResponse = uploadResult.response;
            if (uploadResponse.status() !== 200) {
                throw new Error(
                    `/api/files/upload failed with HTTP ${uploadResponse.status()}: ` +
                    await safeResponseBody(uploadResponse)
                );
            }

            console.log(
                `File upload HTTP stages: presign=${diagnostics.presignResponse?.status() ?? 'not-observed'}, ` +
                `s3Put=${diagnostics.s3PutResponse?.status() ?? 'not-observed'}, complete=${uploadResponse.status()}`
            );
        } finally {
            page.off('response', onResponse);
            page.off('requestfailed', onRequestFailed);
        }

        await page.waitForTimeout(ACTION_TIMEOUT);

        const fileMessageContainer = page.getByTestId('file-message-container').filter({ hasText: message });
        await expect(fileMessageContainer).toBeVisible({ timeout: 10000 });
    } catch (error) {
        console.error('File upload scenario failed:', error.message);
        throw error;
    }
}

/**
 * Artillery 금칙어 처리 시나리오
 */
async function forbiddenWordScenario(page, vuContext) {
    const testUser = vuContext.vars.testUser;
    // NOTE: 환경변수에서 금칙어 목록을 가져오거나 기본값 사용
    const FORBIDDEN_WORDS = process.env.FORBIDDEN_WORDS
        ? process.env.FORBIDDEN_WORDS
            .replace(/^"|"$/g, '') // Remove leading/trailing double quotes
            .split(',')
            .map(word => word.trim().replace(/^"|"$/g, '')) // Remove quotes from each word
        : ['b3sig78jv', '9c0hej6x', 'lbl276sz'];

    try {
        // 1. 랜덤 채팅방 입장
        await joinRandomChatRoomAction(page);
        await expect(page).toHaveURL(new RegExp(`${BASE_URL}/chat/\\w+`));

        // 2. 금칙어 메시지 전송 시도
        const forbiddenWord = FORBIDDEN_WORDS[Math.floor(Math.random() * FORBIDDEN_WORDS.length)];
        await sendMessageAction(page, forbiddenWord);

        // 3. 에러 토스트 확인
        const errorToast = page.getByTestId('toast-error');
        await expect(errorToast).toBeVisible({ timeout: 5000 });

        // 4. 메시지가 전송되지 않았는지 확인
        const sentMessage = page.getByTestId('message-content').filter({ hasText: forbiddenWord });
        await expect(sentMessage).not.toBeVisible();

        vuContext.vars.testUser = testUser;
    } catch (error) {
        console.error('Forbidden word scenario failed:', error.message);
        throw error;
    }
}

module.exports = {
    gotoChatPage,
    chatRoomCreationScenario,
    massMessageScenario,
    fileUploadScenario,
    forbiddenWordScenario,
};
