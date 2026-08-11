package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationExpression;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of RateLimitStore.
 */
@Component
@RequiredArgsConstructor
public class RateLimitMongoStore implements RateLimitStore {
    
    private final MongoTemplate mongoTemplate;

    @Override
    public RateLimit incrementAndGet(String clientId, Instant now, Instant resetExpiresAt) {
        Document expired = new Document("$lte", List.of(
                new Document("$ifNull", List.of("$expiresAt", new Date(0L))),
                Date.from(now)));
        AggregationExpression count = context -> new Document("$cond", List.of(
                expired,
                1,
                new Document("$add", List.of(
                        new Document("$ifNull", List.of("$count", 0)), 1))));
        AggregationExpression expiresAt = context -> new Document("$cond", List.of(
                expired,
                Date.from(resetExpiresAt),
                "$expiresAt"));
        AggregationUpdate update = AggregationUpdate.update()
                .set("clientId").toValue(clientId)
                .set("count").toValueOf(count)
                .set("expiresAt").toValueOf(expiresAt);

        try {
            return findAndIncrement(clientId, update);
        } catch (DuplicateKeyException e) {
            return findAndIncrement(clientId, update);
        }
    }

    private RateLimit findAndIncrement(String clientId, AggregationUpdate update) {
        return mongoTemplate.findAndModify(
                Query.query(Criteria.where("clientId").is(clientId)),
                update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                RateLimit.class);
    }
}
