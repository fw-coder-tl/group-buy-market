package cn.bugstack.types.annotations;

import java.lang.annotation.*;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 注解，动态配置中心标记
 * @create 2025-01-03 15:06
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Documented
public @interface DCCValue {

    String value() default "";

}
