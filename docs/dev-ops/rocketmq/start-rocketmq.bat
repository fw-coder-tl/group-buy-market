@echo off
REM RocketMQ Docker 启动脚本 (Windows)
REM 使用方法: 
REM   启动: start-rocketmq.bat start
REM   停止: start-rocketmq.bat stop
REM   重启: start-rocketmq.bat restart
REM   查看状态: start-rocketmq.bat status
REM   查看日志: start-rocketmq.bat logs

set COMPOSE_FILE=..\docker-compose-rocketmq.yml

if "%1"=="start" goto start
if "%1"=="stop" goto stop
if "%1"=="restart" goto restart
if "%1"=="status" goto status
if "%1"=="logs" goto logs
if "%1"=="clean" goto clean
goto help

:start
echo 🚀 正在启动 RocketMQ...
docker-compose -f %COMPOSE_FILE% up -d
echo.
echo ✅ RocketMQ 启动完成！
echo.
echo 📊 访问地址：
echo    - NameServer: 127.0.0.1:9876
echo    - Broker: 127.0.0.1:10911
echo    - Dashboard: http://127.0.0.1:18080
echo.
echo 💡 提示：
echo    - 查看状态: start-rocketmq.bat status
echo    - 查看日志: start-rocketmq.bat logs
goto end

:stop
echo 🛑 正在停止 RocketMQ...
docker-compose -f %COMPOSE_FILE% down
echo ✅ RocketMQ 已停止！
goto end

:restart
echo 🔄 正在重启 RocketMQ...
docker-compose -f %COMPOSE_FILE% restart
echo ✅ RocketMQ 重启完成！
goto end

:status
echo 📊 RocketMQ 容器状态：
docker-compose -f %COMPOSE_FILE% ps
goto end

:logs
echo 📝 查看 RocketMQ 日志（Ctrl+C 退出）：
docker-compose -f %COMPOSE_FILE% logs -f
goto end

:clean
echo 🧹 清理 RocketMQ 数据...
docker-compose -f %COMPOSE_FILE% down -v
echo ✅ 清理完成！
goto end

:help
echo RocketMQ Docker 管理脚本 (Windows)
echo.
echo 使用方法: start-rocketmq.bat {start^|stop^|restart^|status^|logs^|clean}
echo.
echo 命令说明：
echo   start   - 启动 RocketMQ
echo   stop    - 停止 RocketMQ
echo   restart - 重启 RocketMQ
echo   status  - 查看容器状态
echo   logs    - 查看实时日志
echo   clean   - 停止并清理所有数据
goto end

:end

