--比较线程标识与锁中标识是否一致
if(redis.call("GET", KEYS[1]) == ARGV[1])
then
    --释放锁
    return  redis.call("DEL", KEYS[1])
else
    return 0
end