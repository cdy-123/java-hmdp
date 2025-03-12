package com.hmdp.service;

import com.hmdp.entity.Blog;
import com.hmdp.dto.Result;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {
    public Result queryBlogById(Long id);
    public Result qureyHotBlog(Integer current);
    public Result likeBlog(Long id);
    public Result qureyBlogLikes(Long id);
    public Result saveBlog(Blog blog);
    public Result queryBlogOfFollow(Long max,Integer offset);
}
