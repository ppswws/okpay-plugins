package com.okpay.plugin.epay.handler;

import com.okpay.plugin.model.*;
import com.okpay.plugin.enums.*;
import com.okpay.plugin.sdk.*;

/**
 * 易支付 Submit 处理器。
 *
 * <p>易支付的实际下单在 Handle("create") 完成，
 * Submit 仅返回 Processing 占位。</p>
 */
public final class EpaySubmitHandler extends AbstractBizHandler {

    public EpaySubmitHandler() {
        on(BizType.T_PAY, this::handlePay);
    }

    /** 基类 handle 已按 T_PAY 分发到此，直接返回占位。 */
    private BizResult handlePay(InvokeContext ctx, BizRequest req) {
        return Responses.ing();
    }
}
