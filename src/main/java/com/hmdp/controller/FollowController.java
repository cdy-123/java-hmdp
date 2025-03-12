package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService followService;

    @PutMapping("/{followuserid}/{isFollow}")
    public Result follow(@PathVariable("followuserid") Long followuserid, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followuserid,isFollow);
    }

    @GetMapping("/or/not/{followuserid}")
    public Result isFollow(@PathVariable("followuserid") Long followuserid) {
        return followService.isFollow(followuserid);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }
}
