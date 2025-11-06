package cn.bugstack.api.dto;

import lombok.Data;

@Data
public class TokenRequestDTO {

    // 场景类型（lock_order）
    private String scene;
    // 用户ID
    private String userId;
    // 活动ID
    private Long activityId;

}
