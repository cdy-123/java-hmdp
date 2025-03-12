package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.ILock;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> script;
    static { //加载定义在资源文件中的lua脚本
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
    }

//    private BlockingQueue<VoucherOrder> orderqueue = new ArrayBlockingQueue<>(1024*1024); //阻塞队列

    private static final ExecutorService SECKILL_ORDER_EXEC = Executors.newSingleThreadExecutor(); //线程池

    private IVoucherOrderService proxy; //得到spring封装的动态代理对象

    @PostConstruct //spring的注释，可以在当前类初始化完毕之后执行下面的函数
    private void init() {
        //开启线程
        SECKILL_ORDER_EXEC.submit(new VoucherOrderTask());
    }
//    private class VoucherOrderTask implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //获取阻塞队列中的订单信息
//                    VoucherOrder voucherOrder = orderqueue.take();
//                    //创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error(e.getMessage());
//                }
//            }
//        }
//    }

    //优化之后-用redis里面的Stream消息队列来优化阻塞队列的问题
    private class VoucherOrderTask implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );

                    //获取失败，说明消息队列中没有消息，继续循环等待
                    if(list == null || list.isEmpty()){
                        continue;
                    }

                    //解析list中的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //创建订单
                    handleVoucherOrder(voucherOrder);

                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
                } catch (Exception e) { //出现异常，需要处理pending-list中的消息
                    while (true){
                        //获取pending-list中的订单信息
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                                Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1),
                                StreamOffset.create("stream.orders", ReadOffset.from("0"))
                        );

                        //获取失败，说明pending-list中没有消息，退出
                        if(list == null || list.isEmpty()){
                            break;
                        }

                        //如果有消息，解析list中的消息
                        MapRecord<String, Object, Object> record = list.get(0);
                        Map<Object, Object> values = record.getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                        //创建订单
                        handleVoucherOrder(voucherOrder);

                        //ACK确认
                        stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
                    }
                }
            }
        }
    }

    //优化之后-用redis优惠券秒杀优化，速度更快
//    @Override
//    public Result seckkillVoucher(Long voucherId){
//        //执行lua脚本
//        Long userId = UserHolder.getUser().getId();
//        Long result = stringRedisTemplate.execute(script, Collections.emptyList(),voucherId.toString(),userId.toString());
//
//        //返回1和2都是下单不成功
//        if(result.intValue() != 0){
//            return Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
//        }
//
//        //返回0表示下单成功，把下单信息保存到阻塞队列
//        long orderid = redisIdWorker.nextId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderid);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        orderqueue.add(voucherOrder); //放到阻塞队列
//
//        proxy = (IVoucherOrderService) AopContext.currentProxy(); //得到spring封装的动态代理对象
//
//        return Result.ok(orderid);
//
//    }

    //优化之后-用redis里面的Stream消息队列来优化阻塞队列的问题
    @Override
    public Result seckkillVoucher(Long voucherId){
        //执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(script, Collections.emptyList(),voucherId.toString(),userId.toString(),orderId.toString());

        //返回1和2都是下单不成功
        if(result.intValue() != 0){
            return Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
        }

        //返回0表示下单成功
        proxy = (IVoucherOrderService) AopContext.currentProxy(); //得到spring封装的动态代理对象

        return Result.ok(orderId);

    }


    private void handleVoucherOrder(VoucherOrder voucherOrder){
        Long userid = voucherOrder.getUserId();
        //创建锁对象，一般不会出现问题，但是以防万一
        RLock lock = redissonClient.getLock("lock:order:"+userid.toString()); //对用户id加锁，直接调用redisson封装好的现成锁
        boolean isLock = lock.tryLock(); //尝试获取锁
        if(!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //防止多次下单，也是为了以防万一
        Long userid = voucherOrder.getUserId();
        int count = query().eq("user_id", userid).eq("voucher_id", voucherOrder.getVoucherId()).count(); //判断是否存在该用户的订单
        if (count > 0) {
            log.error("不允许重复下单");
            return;
        }

        //优化之后-用乐观锁CAS方法解决超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) //stock>0 就可以卖，如果严格限制stock等于之前查询的值就会导致很多订单失败，无法全部卖出
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        //创建订单
        save(voucherOrder);
    }

//    @Override
//    public Result seckkillVoucher(Long voucherId){
//        //查询优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //判断是否是秒杀时间
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("活动尚未开始");
//        }
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("活动已经结束");
//        }
//
//        //判断库存是否充足
//        if(voucher.getStock()<1){
//            return Result.fail("库存不足");
//        }
//
//        //线程安全加悲观锁，防止同一个用户超卖
//        Long userid = UserHolder.getUser().getId();
////        synchronized (userid.toString().intern()) { //对id值一样的对象加锁
////            //因为@Transactional加在createVoucherOrder函数上，直接在这里调用createVoucherOrder函数是调用函数本身，不是调用springboot封装的动态代理对象，所以不会实现事务。需要调用spring封装的动态代理对象才能实现事务
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy(); //得到spring封装的动态代理对象
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        //优化之后-用分布式锁解决服务器集群的不一致问题
////        ILock lock = new ILock(stringRedisTemplate,"lock:order:"+userid.toString()); //对用户id加锁，自己写的锁
//        RLock lock = redissonClient.getLock("lock:order:"+userid.toString()); //对用户id加锁，直接调用redisson封装好的现成锁
//        boolean isLock = lock.tryLock(); //尝试获取锁
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy(); //得到spring封装的动态代理对象
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//    }

}
