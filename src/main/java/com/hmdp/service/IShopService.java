package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopService extends IService<Shop> {
    public Result queryById(Long id);
    public Result update(Shop shop);
    public Result queryShopByType(Integer typeId,Integer current,Double x,Double y);
}
