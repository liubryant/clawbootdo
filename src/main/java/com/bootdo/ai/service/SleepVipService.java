package com.bootdo.ai.service;

import com.bootdo.ai.dao.AppUserDao;
import com.bootdo.ai.domain.AppUserDO;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时光睡眠 StoreKit 2 自动续订服务。
 *
 * 只接受 cn.cjym.timesleep 的三种订阅商品，并使用 sleep_apple_* 独立表。
 * 不读写 Navi 综合会员、微信、支付宝以及安卓/鸿蒙订单数据。
 */
@Service
public class SleepVipService {
    private static final Logger log = LoggerFactory.getLogger(SleepVipService.class);
    private static final String BUNDLE_ID = "cn.cjym.timesleep";
    private static final String WEEK_PRODUCT = "cn.sleep.vip.week";
    private static final String MONTH_PRODUCT = "cn.sleep.vip.month";
    private static final String YEAR_PRODUCT = "cn.sleep.vip.year";

    private final JdbcTemplate jdbcTemplate;
    private final AppUserDao appUserDao;
    private final AppAccessTokenService tokenService;
    private final AppleIapVerifier appleIapVerifier;

    public SleepVipService(JdbcTemplate jdbcTemplate, AppUserDao appUserDao,
                           AppAccessTokenService tokenService, AppleIapVerifier appleIapVerifier) {
        this.jdbcTemplate = jdbcTemplate;
        this.appUserDao = appUserDao;
        this.tokenService = tokenService;
        this.appleIapVerifier = appleIapVerifier;
    }

    public Map<String, Object> getAppleMembershipStatus(String phone) {
        AppUserDO user = appUserDao.getByPhone(phone);
        if (user == null) {
            throw new IllegalArgumentException("会员身份不存在");
        }
        List<Map<String, Object>> rows;
        if (isSleepGuestPhone(user.getPhone())) {
            rows = jdbcTemplate.queryForList(
                    "SELECT apple_product_id,expires_at FROM sleep_apple_entitlement " +
                            "WHERE guest_user_id=? ORDER BY expires_at DESC LIMIT 1", user.getId());
        } else {
            rows = jdbcTemplate.queryForList(
                    "SELECT e.apple_product_id,e.expires_at FROM sleep_apple_account_binding b " +
                            "JOIN sleep_apple_entitlement e ON e.original_transaction_id=b.original_transaction_id " +
                            "WHERE b.user_id=? ORDER BY e.expires_at DESC LIMIT 1", user.getId());
        }
        Map<String, Object> status = statusFromRows(rows);
        if (Boolean.TRUE.equals(status.get("active")) || isSleepGuestPhone(user.getPhone())) {
            return status;
        }
        return applySharedMembershipFallback(user.getId(), status);
    }

    @Transactional
    public Map<String, Object> verifyApplePurchase(String phone, String requestAppleProductId,
                                                    String requestTransactionId, String jws) {
        AppUserDO authenticatedUser = StringUtils.isBlank(phone) ? null : appUserDao.getByPhone(phone);
        if (phone != null && authenticatedUser == null) {
            throw new IllegalArgumentException("登录状态已失效，请重新登录");
        }
        AppUserDO loggedInUser = authenticatedUser != null && !isSyntheticGuestPhone(authenticatedUser.getPhone())
                ? authenticatedUser : null;

        AppleIapVerifier.VerifiedTransaction tx = appleIapVerifier.verify(jws);
        if (!BUNDLE_ID.equals(tx.bundleId)) {
            log.warn("时光睡眠苹果内购 bundleId 不匹配 expected={} actual={}", BUNDLE_ID, tx.bundleId);
            throw new IllegalArgumentException("支付凭证与时光睡眠不匹配");
        }
        int durationDays = durationDays(tx.productId);
        if (StringUtils.isNotBlank(requestAppleProductId) && !tx.productId.equals(requestAppleProductId)) {
            throw new IllegalArgumentException("支付商品信息不一致");
        }
        if (StringUtils.isNotBlank(requestTransactionId) && !tx.transactionId.equals(requestTransactionId)) {
            throw new IllegalArgumentException("支付交易信息不一致");
        }
        if (tx.revoked) {
            throw new IllegalArgumentException("该交易已退款，无法开通会员");
        }
        if (StringUtils.isBlank(tx.originalTransactionId)) {
            throw new IllegalArgumentException("支付凭证缺少原始交易号");
        }
        if (tx.expiresDate != null && tx.expiresDate <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("该订阅已过期");
        }
        Date expiresAt = tx.expiresDate == null
                ? new Date(System.currentTimeMillis() + durationDays * 86400000L)
                : new Date(tx.expiresDate);

        AppUserDO guestUser = ensureSleepGuestUser(tx.originalTransactionId);
        upsertEntitlement(tx, guestUser.getId(), expiresAt);
        if (loggedInUser != null) {
            bindAppleAccount(loggedInUser.getId(), tx.originalTransactionId);
        }

        int inserted = jdbcTemplate.update("INSERT IGNORE INTO sleep_apple_order " +
                        "(transaction_id,original_transaction_id,user_id,guest_user_id,apple_product_id," +
                        "expires_at,environment,gmt_create) VALUES (?,?,?,?,?,?,?,NOW())",
                tx.transactionId, tx.originalTransactionId,
                loggedInUser == null ? null : loggedInUser.getId(), guestUser.getId(), tx.productId,
                expiresAt, tx.environment);
        boolean alreadyProcessed = inserted == 0;
        if (alreadyProcessed) {
            log.info("时光睡眠 Apple 交易已处理 tx={}", tx.transactionId);
        } else {
            log.info("时光睡眠 Apple 订阅发放成功 user={} guest={} product={} env={} tx={}",
                    loggedInUser == null ? null : loggedInUser.getId(), guestUser.getId(),
                    tx.productId, tx.environment, tx.transactionId);
        }

        String statusPhone = loggedInUser == null ? guestUser.getPhone() : loggedInUser.getPhone();
        Map<String, Object> result = getAppleMembershipStatus(statusPhone);
        result.put("guestAccessToken", tokenService.issue(guestUser.getPhone()));
        result.put("accountBindingOptional", true);
        result.put("transactionAlreadyProcessed", alreadyProcessed);
        return result;
    }

    @Transactional
    public Map<String, Object> bindAppleGuestMembership(String loggedInPhone, String guestPhone) {
        if (!isSleepGuestPhone(guestPhone)) {
            throw new IllegalArgumentException("时光睡眠游客权益凭证无效");
        }
        AppUserDO loggedInUser = appUserDao.getByPhone(loggedInPhone);
        AppUserDO guestUser = appUserDao.getByPhone(guestPhone);
        if (loggedInUser == null || guestUser == null || isSyntheticGuestPhone(loggedInUser.getPhone())) {
            throw new IllegalArgumentException("会员身份不存在");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT original_transaction_id FROM sleep_apple_entitlement " +
                        "WHERE guest_user_id=? AND expires_at>NOW() LIMIT 1 FOR UPDATE", guestUser.getId());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("没有可同步的时光睡眠 Apple 订阅权益");
        }
        bindAppleAccount(loggedInUser.getId(), String.valueOf(rows.get(0).get("original_transaction_id")));
        return getAppleMembershipStatus(loggedInPhone);
    }

    private Map<String, Object> statusFromRows(List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            result.put("active", false);
            result.put("isVip", false);
            result.put("expiresAt", null);
            result.put("appleActive", false);
            result.put("appleExpiresAt", null);
            result.put("appleProductId", null);
            return result;
        }
        Date expiresAt = (Date) rows.get(0).get("expires_at");
        boolean active = expiresAt != null && expiresAt.after(new Date());
        String formatted = expiresAt == null ? null
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(expiresAt);
        result.put("active", active);
        result.put("isVip", active);
        result.put("expiresAt", formatted);
        result.put("appleActive", active);
        result.put("appleExpiresAt", formatted);
        result.put("appleProductId", rows.get(0).get("apple_product_id"));
        return result;
    }

    /**
     * 已登录用户可能已通过现有统一会员体系开通权益。时光睡眠 Apple 订阅仍然使用
     * sleep_apple_* 独立表；这里只在独立权益无效时只读统一会员到期时间，不写入、
     * 不续期，也不改变 Navi、安卓或鸿蒙的订单与支付逻辑。
     */
    private Map<String, Object> applySharedMembershipFallback(Object userId, Map<String, Object> status) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(expires_at,'%Y-%m-%d %H:%i:%s') AS expires_at_text " +
                        "FROM navi_vip_membership " +
                        "WHERE user_id=? AND expires_at>NOW() ORDER BY expires_at DESC LIMIT 1", userId);
        if (rows.isEmpty()) {
            return status;
        }

        String formatted = String.valueOf(rows.get(0).get("expires_at_text"));
        status.put("active", true);
        status.put("isVip", true);
        status.put("expiresAt", formatted);
        // Apple 专属字段保持为空，客户端会回退读取通用 expiresAt。
        status.put("appleActive", false);
        status.put("appleExpiresAt", null);
        status.put("appleProductId", null);
        status.put("membershipSource", "shared");
        return status;
    }

    private void bindAppleAccount(Object userId, String originalTransactionId) {
        jdbcTemplate.queryForList("SELECT original_transaction_id FROM sleep_apple_entitlement " +
                "WHERE original_transaction_id=? FOR UPDATE", originalTransactionId);
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT user_id FROM sleep_apple_account_binding WHERE original_transaction_id=?",
                originalTransactionId);
        if (!existing.isEmpty() && !String.valueOf(userId).equals(String.valueOf(existing.get(0).get("user_id")))) {
            throw new IllegalArgumentException("该 Apple 订阅已绑定其他账号");
        }
        jdbcTemplate.update("INSERT INTO sleep_apple_account_binding " +
                        "(user_id,original_transaction_id,gmt_create,gmt_modified) VALUES (?,?,NOW(),NOW()) " +
                        "ON DUPLICATE KEY UPDATE original_transaction_id=VALUES(original_transaction_id),gmt_modified=NOW()",
                userId, originalTransactionId);
    }

    private AppUserDO ensureSleepGuestUser(String originalTransactionId) {
        String guestPhone = "sg_" + sha256Hex(originalTransactionId).substring(0, 17);
        AppUserDO existing = appUserDao.getByPhone(guestPhone);
        if (existing != null) {
            return existing;
        }
        AppUserDO created = new AppUserDO();
        created.setPhone(guestPhone);
        created.setAppName("iossleep_apple_guest");
        Date now = new Date();
        created.setGmtCreate(now);
        created.setGmtModified(now);
        try {
            appUserDao.save(created);
            return created;
        } catch (DuplicateKeyException ignored) {
            return appUserDao.getByPhone(guestPhone);
        }
    }

    private void upsertEntitlement(AppleIapVerifier.VerifiedTransaction tx, Object guestUserId, Date expiresAt) {
        jdbcTemplate.update("INSERT INTO sleep_apple_entitlement " +
                        "(original_transaction_id,guest_user_id,latest_transaction_id,apple_product_id,expires_at," +
                        "environment,gmt_create,gmt_modified) VALUES (?,?,?,?,?,?,NOW(),NOW()) " +
                        "ON DUPLICATE KEY UPDATE latest_transaction_id=VALUES(latest_transaction_id)," +
                        "apple_product_id=VALUES(apple_product_id),expires_at=GREATEST(expires_at,VALUES(expires_at))," +
                        "environment=VALUES(environment),gmt_modified=NOW()",
                tx.originalTransactionId, guestUserId, tx.transactionId, tx.productId, expiresAt, tx.environment);
    }

    private int durationDays(String productId) {
        if (WEEK_PRODUCT.equals(productId)) return 7;
        if (MONTH_PRODUCT.equals(productId)) return 30;
        if (YEAR_PRODUCT.equals(productId)) return 365;
        throw new IllegalArgumentException("未知的时光睡眠苹果内购商品: " + productId);
    }

    private boolean isSleepGuestPhone(String phone) {
        return phone != null && phone.startsWith("sg_");
    }

    private boolean isSyntheticGuestPhone(String phone) {
        return phone != null && (phone.startsWith("sg_") || phone.startsWith("ig_")
                || phone.startsWith("ios_guest_"));
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) output.append(String.format("%02x", item & 0xff));
            return output.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法创建时光睡眠匿名会员标识", e);
        }
    }
}
