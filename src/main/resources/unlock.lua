-- 比较线程标识和锁中标识是否一致
if(redis.call('get',KEY[1] == ARGV[1]) then
    -- 释放锁
    return redis.call('del',KEYS[1])
end
return 0