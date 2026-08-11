package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.DirectUploadResponse;
import com.ktb.chatapp.dto.FileAccessUrlResponse;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.model.DirectUpload;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.DirectUploadRepository;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.storage.CloudFrontUrlService;
import com.ktb.chatapp.storage.CloudFrontInvalidationService;
import com.ktb.chatapp.util.FileUtil;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.direct-upload.enabled", havingValue = "true")
public class DirectImageUploadService {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final CloudFrontUrlService cloudFront;
    private final CloudFrontInvalidationService invalidation;
    private final DirectUploadRepository uploads;
    private final FileRepository files;
    private final UserRepository users;
    private final MessageRepository messages;
    private final RoomRepository rooms;
    private final MeterRegistry metrics;

    @Value("${file.s3.bucket}") private String bucket;
    @Value("${file.direct-upload.presign-ttl:PT10M}") private Duration presignTtl;
    @Value("${file.cloudfront.signed-url-ttl:PT5M}") private Duration accessTtl;

    public DirectUploadResponse initiate(String userId, DirectUpload.Purpose purpose,
                                         String originalName, String contentType, long size) {
        try {
            FileUtil.validateImageMetadata(originalName, contentType, size);
            String extension = FileUtil.getFileExtension(originalName).toLowerCase();
            String prefix = purpose == DirectUpload.Purpose.CHAT_IMAGE
                    ? "chat/images/" + userId + "/"
                    : "profiles/" + userId + "/";
            String key = prefix + UUID.randomUUID() + "." + extension;
            Instant now = Instant.now();
            DirectUpload upload = uploads.save(DirectUpload.builder()
                    .userId(userId).purpose(purpose).objectKey(key)
                    .originalName(FileUtil.normalizeOriginalFilename(originalName))
                    .contentType(contentType).expectedSize(size).status(DirectUpload.Status.PENDING)
                    .createdAt(now).expiresAt(now.plus(presignTtl)).build());

            PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType(contentType).contentLength(size).build();
            var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(presignTtl).putObjectRequest(put).build());
            metrics.counter("direct_image_upload_presign_total", "purpose", purpose.name(), "result", "success").increment();
            return new DirectUploadResponse(upload.getId(), key, signed.url().toString(),
                    Map.of("Content-Type", contentType), upload.getExpiresAt());
        } catch (RuntimeException e) {
            metrics.counter("direct_image_upload_presign_total", "purpose", purpose.name(), "result", "failure").increment();
            throw e;
        }
    }

    public FileResponse completeChat(String uploadId, String userId) {
        DirectUpload upload = owned(uploadId, userId, DirectUpload.Purpose.CHAT_IMAGE);
        if (upload.getStatus() == DirectUpload.Status.COMPLETED) {
            return files.findByDirectUploadId(uploadId).map(FileResponse::from)
                    .orElseThrow(() -> new IllegalStateException("완료된 파일 정보를 찾을 수 없습니다."));
        }
        verify(upload);
        File saved;
        try {
            saved = files.save(File.builder()
                    .filename(lastSegment(upload.getObjectKey()))
                    .originalname(upload.getOriginalName()).mimetype(upload.getContentType())
                    .size(upload.getExpectedSize()).path(upload.getObjectKey()).user(userId)
                    .directUploadId(uploadId).uploadDate(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            saved = files.findByDirectUploadId(uploadId).orElseThrow(() -> e);
        }
        markCompleted(upload, saved.getId());
        metrics.counter("direct_image_upload_complete_total", "purpose", "CHAT_IMAGE", "result", "success").increment();
        return FileResponse.from(saved);
    }

    public String completeProfile(String uploadId, String userId) {
        DirectUpload upload = owned(uploadId, userId, DirectUpload.Purpose.PROFILE_IMAGE);
        String signedUrl = cloudFront.signedUrl(upload.getObjectKey(), accessTtl);
        if (upload.getStatus() == DirectUpload.Status.COMPLETED) return signedUrl;
        verify(upload);
        User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        deletePreviousProfile(user.getProfileImage());
        user.setProfileImage(upload.getObjectKey());
        user.setUpdatedAt(LocalDateTime.now());
        users.save(user);
        markCompleted(upload, null);
        metrics.counter("direct_image_upload_complete_total", "purpose", "PROFILE_IMAGE", "result", "success").increment();
        return signedUrl;
    }

    public FileAccessUrlResponse accessUrls(List<String> fileIds, String requesterId) {
        Instant expiresAt = Instant.now().plus(accessTtl);
        List<FileAccessUrlResponse.Item> items = fileIds.stream().distinct().map(fileId -> {
            try {
                File file = files.findById(fileId).orElseThrow(() -> new IllegalArgumentException("파일을 찾을 수 없습니다."));
                Message message = messages.findByFileId(fileId)
                        .orElseThrow(() -> new IllegalArgumentException("파일 메시지를 찾을 수 없습니다."));
                Room room = rooms.findById(message.getRoomId())
                        .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));
                if (!room.getParticipantIds().contains(requesterId)) throw new AccessDeniedException("접근 권한이 없습니다.");
                return FileAccessUrlResponse.Item.success(fileId, cloudFront.signedUrl(file.getPath(), accessTtl), expiresAt);
            } catch (RuntimeException e) {
                return FileAccessUrlResponse.Item.failure(fileId, e.getMessage());
            }
        }).toList();
        return new FileAccessUrlResponse(items);
    }

    private DirectUpload owned(String id, String userId, DirectUpload.Purpose purpose) {
        DirectUpload upload = uploads.findById(id).orElseThrow(() -> new IllegalArgumentException("업로드 요청을 찾을 수 없습니다."));
        if (!upload.getUserId().equals(userId) || upload.getPurpose() != purpose) throw new AccessDeniedException("업로드 접근 권한이 없습니다.");
        if (upload.getStatus() == DirectUpload.Status.FAILED || upload.getStatus() == DirectUpload.Status.EXPIRED)
            throw new IllegalStateException("사용할 수 없는 업로드 요청입니다.");
        if (upload.getExpiresAt().isBefore(Instant.now()) && upload.getStatus() != DirectUpload.Status.COMPLETED) {
            upload.setStatus(DirectUpload.Status.EXPIRED);
            uploads.save(upload);
            deleteObject(upload.getObjectKey());
            throw new IllegalStateException("업로드 요청이 만료되었습니다.");
        }
        return upload;
    }

    private void verify(DirectUpload upload) {
        try {
            var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(upload.getObjectKey()).build());
            if (head.contentLength() != upload.getExpectedSize() || !upload.getContentType().equals(head.contentType())) {
                fail(upload);
                throw new IllegalArgumentException("업로드된 이미지의 크기 또는 형식이 일치하지 않습니다.");
            }
        } catch (NoSuchKeyException e) {
            fail(upload);
            throw new IllegalArgumentException("업로드된 이미지를 찾을 수 없습니다.", e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                fail(upload);
                throw new IllegalArgumentException("업로드된 이미지를 찾을 수 없습니다.", e);
            }
            throw e;
        }
    }

    private void fail(DirectUpload upload) {
        upload.setStatus(DirectUpload.Status.FAILED);
        uploads.save(upload);
        deleteObject(upload.getObjectKey());
        metrics.counter("direct_image_upload_complete_total", "purpose", upload.getPurpose().name(), "result", "failure").increment();
    }

    private void markCompleted(DirectUpload upload, String fileId) {
        upload.setStatus(DirectUpload.Status.COMPLETED);
        upload.setCompletedFileId(fileId);
        upload.setCompletedAt(Instant.now());
        upload.setExpiresAt(Instant.now().plus(Duration.ofHours(24)));
        uploads.save(upload);
    }

    private void deletePreviousProfile(String value) {
        if (value == null || value.isBlank()) return;
        String marker = "/profiles/";
        int index = value.indexOf(marker);
        String key = index >= 0 ? value.substring(index + 1) : value;
        try {
            deleteObject(key);
            invalidation.invalidate(key);
        } catch (RuntimeException ignored) {
            metrics.counter("direct_image_storage_errors_total", "operation", "delete_profile").increment();
        }
    }

    private void deleteObject(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    private String lastSegment(String key) {
        return key.substring(key.lastIndexOf('/') + 1);
    }
}
