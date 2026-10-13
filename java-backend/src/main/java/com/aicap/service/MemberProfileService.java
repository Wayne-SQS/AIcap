package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.dto.MemberProfileDtos;
import com.aicap.entity.MemberProfile;
import com.aicap.entity.User;
import com.aicap.mapper.MemberProfileMapper;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 成员画像服务:每个成员的技术栈 / 工作能力 / 熟悉的开发流程领域各不相同。
 * - 读:GET /api/members/profiles,任何登录用户可见(画像用于分工与排期依据)
 * - 写:PATCH /api/members/{userId}/profile,本人或 admin/owner;整体替换,启动播种保证初始差异化
 */
@Service
@RequiredArgsConstructor
public class MemberProfileService {

    private static final Set<String> PROFILE_ADMIN_ROLES = Set.of("admin", "owner");

    private final UserMapper userMapper;
    private final MemberProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    public List<MemberProfileDtos.ProfileOut> list() {
        List<User> users = userMapper.selectList(new QueryWrapper<User>().orderByAsc("id"));
        Map<Integer, MemberProfile> byUser = new LinkedHashMap<>();
        for (MemberProfile p : profileMapper.selectList(null)) {
            byUser.put(p.getUserId(), p);
        }
        List<MemberProfileDtos.ProfileOut> out = new ArrayList<>();
        for (User u : users) {
            out.add(toOut(u, byUser.get(u.getId())));
        }
        return out;
    }

    /** 整体替换画像:admin/owner 可改任意成员,member 只能改本人;viewer 只读(连本人画像也不可改) */
    @Transactional
    public MemberProfileDtos.ProfileOut save(Integer userId, MemberProfileDtos.ProfileIn in, User actor) {
        if (userId == null) throw ApiException.notFound("成员不存在");
        boolean privileged = PROFILE_ADMIN_ROLES.contains(actor.getRole());
        boolean selfWriter = actor.getId().equals(userId) && "member".equals(actor.getRole());
        if (!privileged && !selfWriter) {
            if ("viewer".equals(actor.getRole())) {
                throw ApiException.forbidden("只读角色不能修改画像");
            }
            throw ApiException.forbidden("只能修改本人画像，或由管理员/负责人代改");
        }
        User target = userMapper.selectById(userId);
        if (target == null) throw ApiException.notFound("成员不存在");

        List<MemberProfileDtos.SkillItem> tech = normalize(in.techStack(), "技术栈");
        List<MemberProfileDtos.SkillItem> caps = normalize(in.capabilities(), "工作能力");
        List<MemberProfileDtos.SkillItem> domains = normalize(in.processDomains(), "开发流程领域");

        MemberProfile profile = profileMapper.selectOne(new QueryWrapper<MemberProfile>().eq("user_id", userId));
        boolean created = profile == null;
        if (created) {
            profile = new MemberProfile();
            profile.setUserId(userId);
        }
        profile.setTitle(in.title().trim());
        profile.setTechStack(write(tech));
        profile.setCapabilities(write(caps));
        profile.setProcessDomains(write(domains));
        profile.setSummary(in.summary().trim());
        profile.setYearsExperience(in.yearsExperience());
        profile.setUpdatedBy(actor.getId());
        profile.setUpdatedAt(LocalDateTime.now());
        if (created) profileMapper.insert(profile);
        else profileMapper.updateById(profile);
        return toOut(target, profile);
    }

    /** 供会话 Agent 的 list_members 等场景复用的画像摘要 */
    public MemberProfileDtos.ProfileOut ofUser(User user) {
        MemberProfile p = profileMapper.selectOne(new QueryWrapper<MemberProfile>().eq("user_id", user.getId()));
        return toOut(user, p);
    }

    // ---------- 内部 ----------

    /** 去空白 + 同维度重名拒绝(避免"Java/ java"这类重复画像项) */
    private List<MemberProfileDtos.SkillItem> normalize(List<MemberProfileDtos.SkillItem> items, String dimension) {
        List<MemberProfileDtos.SkillItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MemberProfileDtos.SkillItem item : items) {
            String name = item.name().trim();
            if (name.isEmpty()) throw ApiException.unprocessable(dimension + "名称不能为空");
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw ApiException.unprocessable(dimension + "内名称不能重复：" + name);
            }
            out.add(new MemberProfileDtos.SkillItem(name, item.level()));
        }
        return out;
    }

    private MemberProfileDtos.ProfileOut toOut(User user, MemberProfile p) {
        return new MemberProfileDtos.ProfileOut(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), user.getColor(),
                user.getCapacityHours() == null ? 60 : user.getCapacityHours(),
                p == null ? "" : p.getTitle(),
                p == null ? List.of() : read(p.getTechStack()),
                p == null ? List.of() : read(p.getCapabilities()),
                p == null ? List.of() : read(p.getProcessDomains()),
                p == null ? "" : p.getSummary(),
                p == null || p.getYearsExperience() == null ? 0 : p.getYearsExperience(),
                p == null ? null : p.getUpdatedAt());
    }

    private String write(List<MemberProfileDtos.SkillItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<MemberProfileDtos.SkillItem> read(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<MemberProfileDtos.SkillItem>>() {
            });
        } catch (Exception e) {
            // 库中被人工写坏的行按空画像返回,不影响其他成员
            return List.of();
        }
    }
}
