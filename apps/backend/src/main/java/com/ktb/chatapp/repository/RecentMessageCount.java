package com.ktb.chatapp.repository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecentMessageCount {

    @Id
    private String roomId;

    private long count;
}
