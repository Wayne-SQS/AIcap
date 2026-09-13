package com.aicap.controller;

import com.aicap.dto.MemberProfileDtos;
import com.aicap.entity.User;
import com.aicap.security.Roles;
import com.aicap.service.MemberProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成员画像接口:技术栈 / 工作能力 / 熟悉的开发流程领域。
 * 读:任何登录用户;写:本人或 admin/owner(服务层判定)。
 */
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberProfileController {

    private final MemberProfileService profileService;

    @GetMapping("/profiles")
    public List<MemberProfileDtos.ProfileOut> profiles() {
        Roles.any();
        return profileService.list();
    }

    @PatchMapping("/{userId}/profile")
    public MemberProfileDtos.ProfileOut updateProfile(@PathVariable Integer userId,
                                                      @Valid @RequestBody MemberProfileDtos.ProfileIn in) {
        User actor = Roles.any();
        return profileService.save(userId, in, actor);
    }
}
