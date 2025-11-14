#!/bin/bash

# RocketMQ Docker 启动脚本
# 使用方法: 
#   启动: ./start-rocketmq.sh start
#   停止: ./start-rocketmq.sh stop
#   重启: ./start-rocketmq.sh restart
#   查看状态: ./start-rocketmq.sh status
#   查看日志: ./start-rocketmq.sh logs

COMPOSE_FILE="../docker-compose-rocketmq.yml"

case "$1" in
    start)
        echo "🚀 正在启动 RocketMQ..."
        docker-compose -f $COMPOSE_FILE up -d
        echo "✅ RocketMQ 启动完成！"
        echo ""
        echo "📊 访问地址："
        echo "   - NameServer: 127.0.0.1:9876"
        echo "   - Broker: 127.0.0.1:10911"
        echo "   - Dashboard: http://127.0.0.1:18080"
        echo ""
        echo "💡 提示："
        echo "   - 查看状态: ./start-rocketmq.sh status"
        echo "   - 查看日志: ./start-rocketmq.sh logs"
        ;;
    stop)
        echo "🛑 正在停止 RocketMQ..."
        docker-compose -f $COMPOSE_FILE down
        echo "✅ RocketMQ 已停止！"
        ;;
    restart)
        echo "🔄 正在重启 RocketMQ..."
        docker-compose -f $COMPOSE_FILE restart
        echo "✅ RocketMQ 重启完成！"
        ;;
    status)
        echo "📊 RocketMQ 容器状态："
        docker-compose -f $COMPOSE_FILE ps
        ;;
    logs)
        echo "📝 查看 RocketMQ 日志（Ctrl+C 退出）："
        docker-compose -f $COMPOSE_FILE logs -f
        ;;
    clean)
        echo "🧹 清理 RocketMQ 数据..."
        docker-compose -f $COMPOSE_FILE down -v
        rm -rf ../rocketmq/broker/logs/* ../rocketmq/broker/store/*
        rm -rf ../rocketmq/namesrv/logs/* ../rocketmq/namesrv/store/*
        echo "✅ 清理完成！"
        ;;
    *)
        echo "RocketMQ Docker 管理脚本"
        echo ""
        echo "使用方法: $0 {start|stop|restart|status|logs|clean}"
        echo ""
        echo "命令说明："
        echo "  start   - 启动 RocketMQ"
        echo "  stop    - 停止 RocketMQ"
        echo "  restart - 重启 RocketMQ"
        echo "  status  - 查看容器状态"
        echo "  logs    - 查看实时日志"
        echo "  clean   - 停止并清理所有数据"
        exit 1
        ;;
esac

