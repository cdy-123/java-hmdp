package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long followuserid, Boolean isFollow){
        Long userid = UserHolder.getUser().getId();

        //判断是关注还是取关
        if(isFollow){
            //关注
            Follow follow = new Follow();
            follow.setUserId(userid);
            follow.setFollowUserId(followuserid);
            boolean success = save(follow);
            if(success){
                //把用户的id放入redis的set集合
                stringRedisTemplate.opsForSet().add("follows:"+userid.toString(), followuserid.toString());
            }
        }else{
            //取关
            boolean success = remove(new QueryWrapper<Follow>().eq("user_id", userid).eq("follow_user_id", followuserid));
            if(success) {
                stringRedisTemplate.opsForSet().remove("follows:" + userid.toString(), followuserid.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followuserid){
        Long userid = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userid).eq("follow_user_id", followuserid).count();
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id){
        Long userid = UserHolder.getUser().getId();
        //求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect("follows:" + userid.toString(), "follows:" + id.toString());
        if(intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //解析集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<UserDTO> users = userService.listByIds(ids).stream().map(user-> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }
}
