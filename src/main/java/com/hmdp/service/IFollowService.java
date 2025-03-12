package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {
    public Result follow(Long followuserid,Boolean isFollow);
    public Result isFollow(Long followuserid);
    public Result followCommons(Long id);
}
