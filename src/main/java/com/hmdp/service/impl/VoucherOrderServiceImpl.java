package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Override

    public Result SeckillVoucher(Long voucherId) {
        //1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        //基于synchronized锁实现
        /*synchronized (userId.toString().intern()) {
        //获取代理对象（事务） 事务释放后获取锁
        IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
        return proxy.createVoucherOrder(voucherId);}*/

        //基于redis锁实现
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock(userId.toString(), stringRedisTemplate);
        RLock lock=redissonClient.getLock("lock:order:" + userId);
        //获取锁
       boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if(!isLock){
            //获取锁失败，返回错误或者重试
            return Result.fail("不允许重复下单");
        }
        try{
        IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
        return proxy.createVoucherOrder(voucherId);
        }finally {
            //释放锁
            lock.unlock();
        }

    }

    @Transactional
        public Result createVoucherOrder(Long voucherId){
            //查询订单 做到一人一单
            Long userId = UserHolder.getUser().getId();

                int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
                if (count > 0) {
                    return Result.fail("一该用户已购买过");
                }
                //5.扣减库存 CAS乐观锁
                boolean success = seckillVoucherService.update()
                        .setSql("stock=stock-1")
                        .eq("voucher_id", voucherId).gt("stock", 0)
                        .update();
                if (!success) {
                    return Result.fail("库存不足");
                }
                //6.创建订单
                VoucherOrder voucherOrder = new VoucherOrder();
                //订单id
                long orderId = redisIdWorker.nextId("order");
                voucherOrder.setId(orderId);
                //用户id
                userId = UserHolder.getUser().getId();
                voucherOrder.setUserId(userId);
                //优惠券id
                voucherOrder.setVoucherId(voucherId);
                save(voucherOrder);
                //7.返回订单id
                return Result.ok(orderId);

        }

}
