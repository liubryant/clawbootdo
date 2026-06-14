package com.bootdo.ai.service.impl;

import com.bootdo.ai.dao.AppUserDao;
import com.bootdo.ai.domain.AppUserDO;
import com.bootdo.ai.service.AppUserService;
import com.bootdo.ai.service.SmsCodeService;
import com.bootdo.ai.vo.AppDeviceInfo;
import com.bootdo.ai.vo.AppUserLoginResult;
import com.bootdo.common.domain.PageDO;
import com.bootdo.common.utils.AESUtils;
import com.bootdo.common.utils.Query;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class AppUserServiceImpl implements AppUserService {
    private static final Pattern MAINLAND_PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final int MIN_PASSWORD_LENGTH = 6;

    @Autowired
    private AppUserDao appUserDao;

    @Autowired
    private SmsCodeService smsCodeService;

    @Override
    public AppUserLoginResult loginByCode(String phone, String code, AppDeviceInfo deviceInfo) {
        String normalizedPhone = normalizePhone(phone);
        if (!MAINLAND_PHONE.matcher(normalizedPhone).matches()) {
            throw new IllegalArgumentException("Invalid phone number");
        }
        if (!smsCodeService.verifyLoginCode(normalizedPhone, code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }

        AppUserDO user = appUserDao.getByPhone(normalizedPhone);
        boolean isNewUser = user == null;
        Date now = new Date();
        if (isNewUser) {
            user = new AppUserDO();
            user.setPhone(normalizedPhone);
            applyDeviceInfo(user, deviceInfo);
            user.setGmtCreate(now);
            user.setGmtModified(now);
            appUserDao.save(user);
        } else {
            applyDeviceInfo(user, deviceInfo);
            user.setGmtModified(now);
            appUserDao.updateLoginInfo(user);
        }
        return new AppUserLoginResult(normalizedPhone, isNewUser);
    }

    @Override
    public AppUserLoginResult loginByPassword(String phone, String password, AppDeviceInfo deviceInfo) {
        String normalizedPhone = normalizePhone(phone);
        if (!MAINLAND_PHONE.matcher(normalizedPhone).matches()) {
            throw new IllegalArgumentException("Invalid phone number");
        }
        if (StringUtils.isBlank(password) || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少为" + MIN_PASSWORD_LENGTH + "位");
        }

        AppUserDO user = appUserDao.getByPhone(normalizedPhone);
        if (user == null) {
            throw new IllegalArgumentException("该手机号未注册，请使用验证码登录");
        }
        if (StringUtils.isBlank(user.getPassword())) {
            throw new IllegalArgumentException("该账号未设置密码，请使用验证码登录");
        }
        if (!password.equals(AESUtils.decrypt(user.getPassword()))) {
            throw new IllegalArgumentException("账号或密码不正确");
        }

        Date now = new Date();
        applyDeviceInfo(user, deviceInfo);
        user.setGmtModified(now);
        appUserDao.updateLoginInfo(user);
        return new AppUserLoginResult(normalizedPhone, false);
    }

    @Override
    public AppUserLoginResult setPassword(String phone, String code, String password) {
        String normalizedPhone = normalizePhone(phone);
        if (!MAINLAND_PHONE.matcher(normalizedPhone).matches()) {
            throw new IllegalArgumentException("Invalid phone number");
        }
        if (StringUtils.isBlank(password) || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少为" + MIN_PASSWORD_LENGTH + "位");
        }
        if (!smsCodeService.verifyLoginCode(normalizedPhone, code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }

        AppUserDO user = appUserDao.getByPhone(normalizedPhone);
        Date now = new Date();
        if (user == null) {
            user = new AppUserDO();
            user.setPhone(normalizedPhone);
            user.setPassword(AESUtils.encrypt(password));
            user.setGmtCreate(now);
            user.setGmtModified(now);
            appUserDao.save(user);
            return new AppUserLoginResult(normalizedPhone, true);
        }

        user.setPassword(AESUtils.encrypt(password));
        user.setGmtModified(now);
        appUserDao.updatePassword(user);
        return new AppUserLoginResult(normalizedPhone, false);
    }

    @Override
    public PageDO<AppUserDO> queryList(Query query) {
        int total = appUserDao.count(query);
        List<AppUserDO> rows = appUserDao.list(query);
        PageDO<AppUserDO> page = new PageDO<>();
        page.setTotal(total);
        page.setRows(rows);
        return page;
    }

    @Override
    public void addUser(String phone, String password) {
        String normalizedPhone = normalizePhone(phone);
        if (!MAINLAND_PHONE.matcher(normalizedPhone).matches()) {
            throw new IllegalArgumentException("请输入正确的手机号");
        }
        if (StringUtils.isBlank(password) || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少为" + MIN_PASSWORD_LENGTH + "位");
        }
        if (appUserDao.getByPhone(normalizedPhone) != null) {
            throw new IllegalArgumentException("该手机号已注册");
        }

        AppUserDO user = new AppUserDO();
        user.setPhone(normalizedPhone);
        user.setPassword(AESUtils.encrypt(password));
        Date now = new Date();
        user.setGmtCreate(now);
        user.setGmtModified(now);
        appUserDao.save(user);
    }

    @Override
    public void removeUser(Long id) {
        appUserDao.remove(id);
    }

    @Override
    public void batchRemove(Long[] ids) {
        appUserDao.batchRemove(ids);
    }

    private void applyDeviceInfo(AppUserDO user, AppDeviceInfo deviceInfo) {
        if (deviceInfo == null) {
            return;
        }
        user.setDeviceModel(deviceInfo.getDeviceModel());
        user.setOsVersion(deviceInfo.getOsVersion());
        user.setAppName(deviceInfo.getAppName());
    }

    private String normalizePhone(String phone) {
        String safe = phone == null ? "" : phone.trim();
        safe = safe.replace(" ", "").replace("-", "");
        if (safe.startsWith("+86")) {
            safe = safe.substring(3);
        } else if (safe.startsWith("86") && safe.length() == 13) {
            safe = safe.substring(2);
        }
        return safe;
    }
}
