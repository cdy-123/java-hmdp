package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate; //因为LoginInterceptor这个类没有交给IOC容器，是用new来创建的，所以不能直接注入，需要用构造函数赋值

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(javax.servlet.http.HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //获取session
//        HttpSession session = request.getSession();
        //优化之后-获取token
        String  token = request.getHeader("authorization");
        if (token == null) { //没有token就放行，让登录拦截器处理
            return true;
        }
//        //获取session中的用户
//        UserDTO user = (UserDTO)session.getAttribute("user");
        //优化之后-获取redis中的用户
        Map<Object,Object> map= stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);

        //判断用户是否存在
        if (map.isEmpty()) { //没有用户就放行，让登录拦截器处理
            return true;
        }
        UserDTO user = BeanUtil.fillBeanWithMap(map,new UserDTO(),false); //将map转换为对象
        //保存用户信息到ThreadLocal
        UserHolder.saveUser(user);

        //优化之后-刷新token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
