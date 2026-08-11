package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.DirectUpload;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DirectUploadRepository extends MongoRepository<DirectUpload, String> {
}
