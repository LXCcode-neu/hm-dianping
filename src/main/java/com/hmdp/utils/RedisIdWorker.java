package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     *开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    public static final long COUNT_BITS = 32;
    public long nextId(String keyPrefix) {
        //keyPrefix为业务前缀

        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号

        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count=stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);

        //3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }

}
