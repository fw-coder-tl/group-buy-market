package cn.bugstack.config;

import cn.bugstack.types.utils.TokenUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description Token过滤器 - 防止订单重复提交
 * @create 2025-11-01
 */
@Slf4j
public class TokenFilter extends OncePerRequestFilter {

    private static final String HEADER_VALUE_NULL = "null";
    private static final String HEADER_VALUE_UNDEFINED = "undefined";
    
    /**
     * Lua脚本：原子性地校验并删除Token
     * 逻辑：
     * 1. 获取Redis中的token值
     * 2. 如果不存在，返回错误
     * 3. 如果值不匹配，返回错误
     * 4. 如果匹配，删除token并返回值
     */
    private static final String LUA_SCRIPT_CHECK_AND_DELETE_TOKEN = 
        "local value = redis.call('GET', KEYS[1])\n" +
        "\n" +
        "if value == false then\n" +
        "    return redis.error_reply('token not exist')\n" +
        "end\n" +
        "\n" +
        "if value ~= ARGV[1] then\n" +
        "    return redis.error_reply('token not valid')\n" +
        "end\n" +
        "\n" +
        "redis.call('DEL', KEYS[1])\n" +
        "return value";
    
    public static final ThreadLocal<String> TOKEN_THREAD_LOCAL = new ThreadLocal<>();

    private final RedissonClient redissonClient;

    public TokenFilter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // 从请求头中获取Token
            String token = request.getHeader("X-Token");
            
            if (token == null || HEADER_VALUE_NULL.equals(token) || HEADER_VALUE_UNDEFINED.equals(token)) {
                log.error("Token校验失败: 请求头中未找到Token");
                writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Token不能为空");
                return;
            }

            // 校验Token的有效性
            boolean isValid = checkTokenValidity(token);
            if (!isValid) {
                // log.error("Token校验失败: Token无效或已过期, token: {}", token);
                // 优化：降低日志级别，减少日志内容，避免高并发IO瓶颈
                if (log.isDebugEnabled()) {
                    log.warn("Token校验失败: Token无效或已过期, token: {}", token);
                }
                writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Token无效或已被使用");
                return;
            }

            // Token有效，继续执行
            filterChain.doFilter(request, response);
        } finally {
            TOKEN_THREAD_LOCAL.remove();
        }
    }

    /**
     * 校验Token有效性
     * <p>
     * 1. 解密token得到tokenKey
     * 2. 使用Lua脚本原子性地校验并删除token
     * 3. 如果token存在且值匹配，则删除并返回true
     * 4. 否则返回false
     * </p>
     */
    private boolean checkTokenValidity(String token) {
        try {
            // 解密token得到原始的tokenKey
            String tokenKey = TokenUtil.getTokenKeyByValue(token);
            
            // 执行Lua脚本：原子性地校验并删除token
            String result = redissonClient.getScript().eval(
                    RScript.Mode.READ_WRITE,
                    LUA_SCRIPT_CHECK_AND_DELETE_TOKEN,
                    RScript.ReturnType.VALUE,
                    Collections.singletonList(tokenKey),
                    token
            );

            TOKEN_THREAD_LOCAL.set(result);
            return result != null;
        } catch (RedisException e) {
            // Redis异常通常是连接问题，可以保留Error级别，但要注意频率
            log.error("Token校验失败: Redis异常", e);
            return false;
        } catch (Exception e) {
            // 优化：解密异常通常是攻击或错误Token导致，无需打印堆栈，改为Warn级别
            // log.error("Token校验失败: 解密异常", e);
            log.warn("Token校验失败: 格式错误或解密失败");
            return false;
        }
    }

    /**
     * 写入错误响应
     */
    private void writeErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        String json = String.format("{\"code\":\"E0008\",\"info\":\"%s\"}", message);
        response.getWriter().write(json);
    }
}

