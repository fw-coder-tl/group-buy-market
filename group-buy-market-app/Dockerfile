# 基础镜像（包含 JDK 工具，支持 jps/jcmd 及 Attach）
FROM openjdk:8-jdk-slim

# 作者
MAINTAINER xiaofuge

# 配置
ENV PARAMS=""

# 时区
ENV TZ=PRC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 添加应用
ADD target/group-buy-market-app.jar /group-buy-market-app.jar

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /group-buy-market-app.jar $PARAMS"]