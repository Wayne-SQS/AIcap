package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 用户表(对齐 FastAPI models.User;密码为 passlib $2b$ bcrypt 哈希) */
@Data
@TableName("users")
public class User {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String username;
    private String displayName;
    private String role;
    private String passwordHash;
    private String color;
    /** 六周可用容量(小时);成员负载视图的分母 */
    private Integer capacityHours;
}
