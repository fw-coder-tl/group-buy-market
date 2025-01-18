package cn.bugstack.types.design.framework.link.model2.handler;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 逻辑处理器
 * @create 2025-01-18 09:43
 */
public interface ILogicHandler<T, D, R> {

    default R next(T requestParameter, D dynamicContext) {
        return null;
    }

    R apply(T requestParameter, D dynamicContext) throws Exception;

}
