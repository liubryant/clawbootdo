package com.bootdo.ai.service;

import com.bootdo.ai.domain.AppUserDO;
import com.bootdo.ai.vo.AppDeviceInfo;
import com.bootdo.ai.vo.AppUserLoginResult;
import com.bootdo.common.domain.PageDO;
import com.bootdo.common.utils.Query;

public interface AppUserService {
    /**
     * 手机号 + 短信验证码登录。验证码正确即登录成功，账号不存在时自动注册。
     */
    AppUserLoginResult loginByCode(String phone, String code, AppDeviceInfo deviceInfo);

    /**
     * 手机号 + 密码登录。仅当账号已存在且已设置过密码时才能登录成功。
     */
    AppUserLoginResult loginByPassword(String phone, String password, AppDeviceInfo deviceInfo);

    /**
     * 设置/重置登录密码。需通过短信验证码确认身份，账号不存在时会自动注册。
     */
    AppUserLoginResult setPassword(String phone, String code, String password);

    /**
     * App 端用户注销账号。需通过短信验证码确认身份，删除后账号及登录态永久失效。
     */
    void removeAccount(String phone, String code);

    /**
     * 后台管理：分页查询 App 注册用户列表。
     */
    PageDO<AppUserDO> queryList(Query query);

    /**
     * 后台管理：新增用户，手机号 + 密码，密码使用 AES 加密存储。
     */
    void addUser(String phone, String password);

    /**
     * 后台管理：删除单个用户。
     */
    void removeUser(Long id);

    /**
     * 后台管理：批量删除用户。
     */
    void batchRemove(Long[] ids);
}
