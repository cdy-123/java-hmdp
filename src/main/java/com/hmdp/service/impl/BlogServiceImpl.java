package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id){
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

        //查询当前用户是否点赞了
        this.isBlogLiked(blog);

        return Result.ok(blog);
    }

    @Override
    public Result qureyHotBlog(Integer current){
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //查询当前用户是否点赞了
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id){
        //获取当前登录的用户
        Long userId = UserHolder.getUser().getId();

        //判断当前用户是否已经点赞过了
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY +id.toString(),userId.toString());
        if(score == null){
            //点赞
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id",id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY+id.toString(),userId.toString(),System.currentTimeMillis());
            }
        }else {
            //取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id",id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY+id.toString(),userId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result qureyBlogLikes(Long id){
        //查询top5点赞的用户
        Set<String> top5Id = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id.toString(), 0, 4);
        if(top5Id == null || top5Id.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5Id.stream().map(Long::valueOf).collect(Collectors.toList());

        //根据id查询用户
        String idstr = StrUtil.join(",",ids);
        List<UserDTO> users = userService.query().in("id",ids).last("ORDER BY FIELD(id,"+idstr+")").list()  //使用order by防止数据库查询乱序
                .stream().map(user-> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog){
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //保存笔记到数据库
        boolean success = save(blog);
        if(!success){
            return Result.fail("笔记保存失败");
        }

        //查询笔记作者的粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        //推送给粉丝
        for(Follow follow : follows){
            Long userId = follow.getUserId();
            stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY+userId.toString(),blog.getId().toString(),System.currentTimeMillis());
        }

        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max,Integer offset){
        Long userId = UserHolder.getUser().getId();
        //查看收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + userId.toString(), 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //解析数据
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long mintime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> typedTuple : typedTuples){
            //记录id
            ids.add(Long.valueOf(typedTuple.getValue()));
            //记录最后一个的时间戳
            long time = typedTuple.getScore().longValue();
            if(time == mintime){
                os++;
            }else{
                os=1;
                mintime=time;
            }
        }

        //根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELD(id,"+idStr+")").list();

        //丰富blog的信息
        for(Blog blog : blogs){
            Long userid = blog.getUserId();
            User user = userService.getById(userid);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //查询当前用户是否点赞了
            this.isBlogLiked(blog);
        }

        //封装结果
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setOffset(os);
        result.setMinTime(mintime);
        return Result.ok(result);
    }

    public void isBlogLiked(Blog blog){
        //获取当前登录的用户
        UserDTO user = UserHolder.getUser();
        if (user == null) { //用户未登录，无需查询点赞
            return;
        }
        Long userId = user.getId();

        //判断当前用户是否已经点赞过了
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY+blog.getId().toString(),userId.toString());
        blog.setIsLike(score!=null);
    }
}
