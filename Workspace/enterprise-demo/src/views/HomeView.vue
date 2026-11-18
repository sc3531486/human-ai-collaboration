<script setup>
/*
 * 渠道首页：纯渠道方页面，不加载 SDK——SDK 只在用户进入「远程视频银行」时挂载，
 * 离开即销毁。这体现了渠道方完全掌控页面生命周期。
 */
function goBank() {
    window.location.hash = '#/bank';
}
</script>

<template>
    <div>
        <section class="bank-welcome">
            <div>
                <p class="eyebrow">企业金融服务</p>
                <h1>上午好，晨曦集团</h1>
                <p>当前登录人：王财务 · 操作员编号 310002</p>
            </div>
            <div class="login-meta">上次登录：今天 09:18<br>安全等级：高</div>
        </section>

        <section class="account-overview">
            <article><span>对公活期账户</span><strong>¥ 8,650,000.00</strong><small>6214 **** **** 3456</small></article>
            <article><span>待授权指令</span><strong>3 笔</strong><small>请及时处理待办事项</small></article>
            <article><span>本月收支</span><strong>¥ 1,286,400.00</strong><small>较上月 +8.2%</small></article>
        </section>

        <section class="bank-section">
            <div class="section-title"><h2>常用服务</h2><button type="button">全部服务</button></div>
            <div class="service-grid">
                <button class="service-item" type="button"><b>转</b><span>转账汇款</span><small>单笔及批量付款</small></button>
                <button class="service-item" type="button"><b>账</b><span>账户查询</span><small>余额与交易明细</small></button>
                <button class="service-item" type="button"><b>薪</b><span>代发工资</span><small>批量代发服务</small></button>
                <button class="service-item featured" type="button" @click="goBank">
                    <b>视</b><span>远程视频银行</span><small>专属客户经理在线服务</small><em>发起服务</em>
                </button>
            </div>
        </section>

        <section class="bank-section split-grid">
            <article class="host-card"><h2>待办事项</h2><p>暂无需要您处理的授权指令。</p></article>
            <article class="host-card"><h2>服务提醒</h2><p>账户资金变动、交易回单和远程服务记录可在消息中心查阅。</p></article>
        </section>
    </div>
</template>
