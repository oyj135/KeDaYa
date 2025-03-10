package com.yj.kedaya.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户编辑资料请求
 *
 * @author <a href="https://www.ouyangjian.com/">YJ.渔夫.星辰</a>
 * @package com.yj.kedaya.model.dto.user
 * @date 2025/3/10 21:28
 */

@Data
public class UserEditRequest implements Serializable {

    /**
     * 手机号
     */
    private String phoneNumber;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 年级
     */
    private String grade;

    /**
     * 工作经验
     */
    private String workExperience;

    /**
     * 擅长方向
     */
    private String expertiseDirection;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;
}
