package cn.bugstack.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author liang.tian
 * @description Token场景枚举
 * @create 2025-11-01
 */
@Getter
@AllArgsConstructor
public enum TokenSceneEnum {

    LOCK_ORDER("lock_order", "锁单场景"),
    ;

    private final String scene;
    private final String desc;

    public static TokenSceneEnum getByScene(String scene) {
        for (TokenSceneEnum tokenSceneEnum : TokenSceneEnum.values()) {
            if (tokenSceneEnum.getScene().equals(scene)) {
                return tokenSceneEnum;
            }
        }
        return null;
    }
}

