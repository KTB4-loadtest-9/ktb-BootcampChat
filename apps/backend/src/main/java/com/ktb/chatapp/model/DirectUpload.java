package com.ktb.chatapp.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "direct_uploads")
public class DirectUpload {
    @Id
    private String id;
    private String userId;
    private Purpose purpose;
    @Indexed(unique = true)
    private String objectKey;
    private String originalName;
    private String contentType;
    private long expectedSize;
    private Status status;
    private String completedFileId;
    private Instant createdAt;
    @Indexed(expireAfter = "0s")
    private Instant expiresAt;
    private Instant completedAt;

    public enum Purpose { CHAT_IMAGE, PROFILE_IMAGE }
    public enum Status { PENDING, COMPLETED, FAILED, EXPIRED }
}
