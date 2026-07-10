package com.bootdo.ai.dto;

/** 苹果内购校验请求体。 */
public class AppleVerifyRequest {
    private String productId;        // 后端套餐 id(navi_vip_product.id)，用于记录订单，非发放依据
    private String appleProductId;   // App Store Connect 商品 id，仅作辅助，发放以 JWS 内的 productId 为准
    private String transactionId;    // 客户端上报的交易号，仅作日志，发放以 JWS 内的 transactionId 为准
    private String jws;              // StoreKit 2 交易凭证(必需)
    private String platform;
    private String bundleId;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public String getAppleProductId() { return appleProductId; }
    public void setAppleProductId(String appleProductId) { this.appleProductId = appleProductId; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getJws() { return jws; }
    public void setJws(String jws) { this.jws = jws; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getBundleId() { return bundleId; }
    public void setBundleId(String bundleId) { this.bundleId = bundleId; }
}
