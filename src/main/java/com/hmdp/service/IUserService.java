package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

public interface IUserService extends IService<User> {
    public Result sendCode(String phone, javax.servlet.http.HttpSession session);
    public Result login(LoginFormDTO loginForm, HttpSession session);
    public Result sign();
    public Result signCount();
}
