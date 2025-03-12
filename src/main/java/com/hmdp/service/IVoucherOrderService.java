package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {
    public Result seckkillVoucher(Long voucherId);
//    public Result createVoucherOrder(Long voucherId);
    public void createVoucherOrder(VoucherOrder voucherOrder);

}
