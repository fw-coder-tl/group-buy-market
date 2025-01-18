package cn.bugstack.types.design.framework.link.model2.chain;

import cn.bugstack.types.design.framework.link.model2.handler.ILogicHandler;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 业务链路
 * @create 2025-01-18 10:27
 */
public class BusinessLinkedList<T, D, R> extends LinkedList<ILogicHandler<T, D, R>> implements ILogicHandler<T, D, R>{

    public BusinessLinkedList(String name) {
        super(name);
    }

    @Override
    public R apply(T requestParameter, D dynamicContext) throws Exception {
        Node<ILogicHandler<T, D, R>> current = this.first;
        do {
            ILogicHandler<T, D, R> item = current.item;
            R apply = item.apply(requestParameter, dynamicContext);
            if (null != apply) return apply;

            current = current.next;
        } while (null != current);

        return null;
    }

}
