# CloudFront + S3 이미지 업로드 프런트엔드 인수인계

## 1. 적용 범위와 호환성

- 채팅 이미지와 프로필 이미지만 S3 직접 업로드 대상이다.
- PDF 등 이미지가 아닌 파일은 기존 `POST /api/files/upload`를 계속 사용한다.
- 기존 업로드·조회·다운로드·삭제 API는 유지된다.
- 프런트 빌드 변수 `NEXT_PUBLIC_DIRECT_IMAGE_UPLOAD_ENABLED=true`일 때만 신규 흐름이 활성화된다.
- 백엔드의 `DIRECT_IMAGE_UPLOAD_ENABLED=true`, `FILE_STORAGE_TYPE=s3`도 함께 설정되어야 한다.
- 신규 흐름 실패 시 기존 업로드 API로 자동 fallback하지 않는다. 사용자가 다시 시도하게 해야 중복 객체와 중복 메시지를 방지할 수 있다.

## 2. 채팅 이미지 업로드

### 처리 순서

1. 기존과 동일하게 확장자, MIME 타입, 5MB 제한을 프런트에서 검사한다.
2. 백엔드에 presigned PUT URL을 요청한다.
3. 응답의 `uploadUrl`로 이미지를 직접 PUT 한다. 이 요청에는 앱 JWT를 넣지 않는다.
4. PUT 성공 후 complete API를 호출한다.
5. complete 응답의 `file`을 기존 Socket.IO 파일 메시지의 `fileData`로 사용한다.

### Presign

```http
POST /api/files/chat-images/presign
Authorization: Bearer <token>
Content-Type: application/json

{
  "originalName": "photo.png",
  "contentType": "image/png",
  "size": 1048576
}
```

```json
{
  "uploadId": "mongo-upload-id",
  "objectKey": "chat/images/user-id/uuid.png",
  "uploadUrl": "https://s3-presigned-put-url",
  "requiredHeaders": {
    "Content-Type": "image/png"
  },
  "expiresAt": "2026-08-11T01:10:00Z"
}
```

S3 PUT 요청에는 `requiredHeaders`를 그대로 사용하고 파일 본문만 전송한다. URL, 서명 쿼리, JWT를 로그나 오류 수집 도구에 기록하지 않는다.

### Complete

```http
POST /api/files/chat-images/{uploadId}/complete
Authorization: Bearer <token>
```

```json
{
  "success": true,
  "file": {
    "_id": "file-id",
    "filename": "uuid.png",
    "originalname": "photo.png",
    "mimetype": "image/png",
    "size": 1048576,
    "user": "user-id",
    "uploadDate": "2026-08-11T10:00:00"
  }
}
```

complete는 멱등하게 처리된다. 네트워크 타임아웃으로 응답을 못 받았다면 같은 `uploadId`로 complete만 다시 호출한다. S3 PUT부터 반복하지 않는다.

현재 구현은 [fileService.js](../../frontend/services/fileService.js)에서 기능 플래그 분기, 진행률, 취소, complete 호출을 처리한다. 기존 채팅 훅과 Socket.IO 메시지 형식은 바뀌지 않는다.

## 3. 채팅 이미지 조회

채팅 이미지는 S3나 CloudFront 공개 URL을 직접 조립하지 않는다. 화면에 필요한 `file._id`를 다음 API에 최대 50개까지 전달한다.

```http
POST /api/files/chat-images/access-urls
Authorization: Bearer <token>
Content-Type: application/json

{
  "fileIds": ["file-id-1", "file-id-2"]
}
```

```json
{
  "items": [
    {
      "fileId": "file-id-1",
      "url": "https://cloudfront-signed-url",
      "expiresAt": "2026-08-11T01:05:00Z",
      "error": null
    },
    {
      "fileId": "file-id-2",
      "url": null,
      "expiresAt": null,
      "error": "접근 권한이 없습니다."
    }
  ]
}
```

- 응답은 부분 성공 방식이므로 항목별 `url` 또는 `error`를 처리한다.
- URL 유효기간은 기본 5분이다.
- 만료 30초 전부터 새 URL을 발급받는다.
- 같은 렌더 사이클의 요청은 `fileService`가 microtask 단위로 묶고, 발급된 URL은 메모리에 캐시한다.
- Signed URL을 localStorage나 영구 상태에 저장하지 않는다.
- 권한 오류가 발생한 이미지는 placeholder와 사용자 친화적인 오류 문구로 처리한다.
- 기존 `/api/files/view/{filename}`과 `/download/{filename}`도 유지되므로 직접 URL 기능을 끈 환경에서는 기존 경로를 사용한다.

## 4. 프로필 이미지

프로필도 `presign → S3 PUT → complete` 순서다.

```http
POST /api/users/profile-image/presign
POST /api/users/profile-image/{uploadId}/complete
```

presign 요청과 응답은 채팅 이미지와 동일하다. complete 성공 응답은 기존 프로필 응답 형태다.

```json
{
  "success": true,
  "message": "프로필 이미지가 업데이트되었습니다.",
  "imageUrl": "https://images.example.com/profiles/user-id/uuid.webp"
}
```

- `imageUrl`은 업로드 직후 표시할 수 있는 만료형 CloudFront Signed URL이다. DB에는 URL이 아니라 object key만 저장된다.
- 사용자·메시지 응답의 `profileImage`는 이미지 존재 여부와 key 전달용이며, 화면에서는 직접 URL로 조립하지 않는다.
- 아바타는 `POST /api/users/profile-images/access-urls`에 최대 50개 `userIds`를 보내 만료형 URL을 일괄 발급받는다.
- 발급 URL은 만료 30초 전까지만 메모리에 캐시하고 localStorage에는 저장하지 않는다.
- 로컬 저장 모드는 access API가 반환한 상대 경로에 현재 token/sessionId를 붙여 인증된 fallback을 사용한다.
- 기존 `DELETE /api/users/profile-image`는 그대로 사용한다.
- 교체·삭제 후 기존 이미지 객체 삭제와 CloudFront invalidation은 백엔드가 처리한다.

## 5. 오류 처리와 QA 체크리스트

- presign 400: 확장자/MIME/크기 오류를 사용자에게 표시한다.
- presign 401/403: 기존 세션 만료 처리로 연결한다.
- S3 PUT 실패: complete를 호출하지 않고 재시도 UI를 표시한다.
- complete 409: 만료·실패 상태이므로 presign부터 새로 시작한다.
- complete 응답 유실: 동일 `uploadId`로 complete만 다시 호출한다.
- access-urls 일부 실패: 성공한 이미지는 표시하고 실패 항목만 placeholder 처리한다.
- 업로드 취소 시 S3 PUT을 취소하고 메시지를 전송하지 않는다.
- 플래그 OFF에서 이미지와 PDF가 모두 기존 API로 정상 동작하는지 확인한다.
- 플래그 ON에서 이미지는 S3로, PDF는 기존 API로 전송되는지 Network 패널에서 확인한다.
- S3 요청에 앱 Authorization 헤더가 포함되지 않는지 확인한다.
- 채팅방 비참가자가 Signed URL을 발급받지 못하는지 확인한다.

## 6. 배포 순서

1. S3 CORS와 CloudFront 배포 동작을 준비한다.
2. 백엔드를 `DIRECT_IMAGE_UPLOAD_ENABLED=false`로 배포한다.
3. 프런트엔드를 `NEXT_PUBLIC_DIRECT_IMAGE_UPLOAD_ENABLED=false`로 빌드·배포한다.
4. 테스트 환경에서 백엔드와 프런트 플래그를 함께 켠다.
5. QA와 부하 검증 후 운영 빌드의 프런트 플래그를 켠다.
6. 장애 시 프런트 플래그를 끈 빌드로 되돌려 기존 API를 사용한다.
