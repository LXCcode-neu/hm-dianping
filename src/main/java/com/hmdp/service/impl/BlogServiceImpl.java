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
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private UserServiceImpl userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Override
    public Result queryHotBlog(Integer current) {
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
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询top5点赞用户
        String key = "blog:liked:" + id;
        Set<String> top5=stringRedisTemplate.opsForZSet().range(key,0,4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr= StrUtil.join(",", ids);
        List<UserDTO> userDTOs=userService.query().in("id", ids)
                .last("order by field (id,"+ idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess =save(blog);
        if (!isSuccess) {
            return Result.fail("发布失败！");
        }
        //查询该作者所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送给该id所有粉丝
        for (Follow follow : follows) {
            Long followId = follow.getUserId();
            String key = "feed" + followId;
            //保存到redis
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),  System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());

    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查询收件箱
        String key = "feed" + userId;
         Set<ZSetOperations.TypedTuple<String>> typedTuples= stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key,0,max,offset,2);
         if(typedTuples==null||typedTuples.isEmpty()){
             return Result.ok();
         }
        //解析数据 blogId minTime offset
        List<Long>ids=new ArrayList<>(typedTuples.size());
         long minTime = 0;
         int os=1;
        for(ZSetOperations.TypedTuple<String> tuple:typedTuples){
            //获取id
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));
            //获取offset值
            long time = tuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else{
                minTime=time;
                os=1;
            }
        }
        //根据id查询blog
        String idStr=StrUtil.join(",",ids);
        List<Blog>blogs=query()
                .in("id",ids).last("order by field(id," +idStr +")").list();
        for (Blog blog : blogs) {
            //查询有关用户
            Long Id = blog.getUserId();
            User user = userService.getById(Id);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //查询blog是否被点赞
            isBlogLiked(blog);

        }
        //封装并返回
        ScrollResult r=new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    @Override
    public Result queryBlogById(Long id) {
       Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        //查询有关用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user=UserHolder.getUser();
        if(user==null)
        {
            return;
        }
        Long userId = user.getId();
        //判断当前用户是否已经点赞
        String key="blog:liked:"+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId= UserHolder.getUser().getId();
        //判断是否点赞
        String key="blog:liked:"+id;
        Double score=stringRedisTemplate.opsForZSet().score(key,userId.toString());
        //如果未点赞，可以点赞
        //数据库点赞数+1 保存用户到redis集合
        if(score==null){
            boolean isSuccess=update().setSql("liked=liked+1").eq("id",id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
            return Result.ok();
        }
        else{
            //如果已点赞，则不能重复点赞
            boolean isSuccess=update().setSql("liked=liked-1").eq("id",id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
            return Result.ok();
        }
    }

}
