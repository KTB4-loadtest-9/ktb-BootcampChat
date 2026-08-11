package com.ktb.chatapp.config;

import com.ktb.chatapp.model.Message;
import org.bson.Document;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

    @Bean
    ApplicationRunner ensureMessageIndexes(MongoOperations mongoOperations) {
        return args -> mongoOperations.indexOps(Message.class)
            .ensureIndex(new CompoundIndexDefinition(
                new Document("room", 1).append("timestamp", 1))
                .named("room_timestamp_idx"));
    }
}
