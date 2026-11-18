/*
 * SDK 端壳 DOM：navis mobile_balance.html 的结构移植为原生元素构建（契约 §8.1 禁 Vue）。
 * 只使用 vb- 前缀类名（由 scripts/build-css.mjs 生成的隔离样式对应），不产生任何全局选择器。
 * SDK 只拥有传入的容器：根元素与告知书浮层都收在容器内（告知书原为 fixed，这里降级为容器内 absolute）。
 */
import { icon } from '../ui/VbIcon.js';

const DEFAULT_CHIPS = [
    ['💳', '给王总转账200万'],
    ['💳', '给李四转账1000元'],
    ['💰', '查询账户余额'],
    ['📋', '转账限额是多少？'],
    ['📈', '热门理财推荐'],
    ['🧑‍💼', '转人工客服'],
];

const CHAT_HOME_SERVICES = [
    { title: '账户余额查询', prompt: '查询账户余额' },
    { title: '转账汇款', prompt: '我要办理转账汇款' },
    { title: '密码重置', prompt: '我要重置密码' },
    { title: '联系人工坐席', prompt: '转人工客服' },
];

function el(tag, className, attrs = {}) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    Object.entries(attrs).forEach(([key, value]) => {
        if (value === false || value === null || value === undefined) return;
        if (key === 'text') node.textContent = value;
        else node.setAttribute(key, value === true ? '' : String(value));
    });
    return node;
}

function consentOverlay() {
    const overlay = el('div', 'vb-consent-overlay', { role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'vb-consent-title' });
    // 基线样式为 position:fixed——SDK 不拥有宿主页面，收进容器内 absolute。
    overlay.style.position = 'absolute';
    // 仅 Flowable 进入人工视频节点时由 consent.show() 打开；进入数字人服务不展示。
    overlay.style.display = 'none';

    const card = el('div', 'vb-consent-card');
    card.appendChild(icon('shield', 30));
    card.appendChild(el('span', 'vb-consent-eyebrow', { text: '安心服务，从知情开始' }));
    const title = el('h2', null, { text: '开启您的远程银行服务', id: 'vb-consent-title' });
    card.appendChild(title);
    card.appendChild(el('p', 'vb-consent-intro', { text: '我是晨曦，很高兴为您服务。开始办理前，请了解以下事项。' }));

    const items = el('div', 'vb-consent-items');
    [
        ['video', '业务过程录音录像', '办理时会请求摄像头与麦克风权限，用于记录身份核验、业务确认和视频见证过程。'],
        ['archive', '业务记录关联留存', '录制分段、业务信息与操作记录将上传服务端，供业务回溯与影像调阅。'],
    ].forEach(([name, heading, body]) => {
        const row = el('div');
        row.appendChild(icon(name, 20));
        const p = el('p');
        const strong = el('strong', null, { text: heading });
        p.appendChild(strong);
        p.appendChild(el('span', null, { text: body }));
        row.appendChild(p);
        items.appendChild(row);
    });
    card.appendChild(items);
    card.appendChild(el('div', 'vb-consent-demo', { text: '当前为业务演示环境，请使用测试信息。' }));

    const accept = el('button', 'vb-consent-primary', { type: 'button', id: 'vb-btn-accept-consent' });
    accept.appendChild(document.createTextNode('同意并开始服务 '));
    accept.appendChild(icon('arrow', 18));
    card.appendChild(accept);
    card.appendChild(el('p', 'vb-consent-bottom', { text: '暂不办理时，您可以关闭本页面。' }));
    overlay.appendChild(card);
    return overlay;
}

function stageArea(options = {}) {
    const stage = el('div', 'vb-stage-container', { id: 'vb-main-stage' });

    const badge = el('div', 'vb-stage-badge');
    const dot = el('span', 'vb-stage-dot');
    const text = el('span', null, { text: '晨曦 · 在线为您服务' });
    const replay = el('button', null, { type: 'button', title: '点击重听数字人语音', style: 'background:rgba(255,255,255,0.25);border:none;color:white;font-size:10px;padding:1px 6px;border-radius:10px;cursor:pointer;margin-left:4px;' });
    replay.appendChild(icon('volume', 14));
    badge.append(dot, text, replay);
    stage.appendChild(badge);

    // 数字人以渠道配置的二维透明形象独立悬浮在页面上，不占用业务舞台。
    const avatar = el('div', 'vb-avatar-layer');
    const wrapper = el('div', 'vb-digital-human-wrapper');
    // 形象资源由渠道配置。SDK 不能假定宿主根路径存在 /images，避免嵌入渠道后丢失数字人。
    const avatarUrl = options.avatarUrl || './images/shuziren.png';
    const img = el('img', 'vb-digital-human-img', { src: avatarUrl, alt: '数字人客户经理晨曦', id: 'vb-digital-human-img' });
    if (options.avatarStillUrl) {
        const picture = el('picture');
        picture.append(el('source', null, { media: '(prefers-reduced-motion: reduce)', srcset: options.avatarStillUrl }), img);
        wrapper.appendChild(picture);
    } else {
        wrapper.appendChild(img);
    }
    avatar.appendChild(wrapper);

    const info = el('div', 'vb-digital-human-info');
    const tag = el('div', 'vb-dh-name-tag');
    const dhTitle = el('span', 'vb-dh-title', { text: '您好，我是晨曦' });
    const dhBadge = el('span', 'vb-dh-badge', { text: '您的数字人客户经理' });
    tag.append(dhTitle, dhBadge);
    const wave = el('div', 'vb-voice-wave-bar');
    for (let i = 0; i < 5; i += 1) wave.appendChild(el('span', 'vb-wave-item'));
    info.append(tag, wave);
    // 业务名称留在舞台内；人物本体作为页面悬浮层，由 buildShell 挂到 phone 上。
    stage.appendChild(info);

    // video-widget 落位容器（§2.9.1 主舞台：座席视频 / 本地画中画 / 播控工具条由控件渲染）
    const widgetSlot = el('div', 'vb-widget-slot');
    widgetSlot.style.cssText = 'position:absolute;inset:0;display:none;z-index:11;';

    // 本地预览画中画：控件未接入时由 media.js 兜底展示（控件接入后由控件承担）
    const pip = document.createElement('video');
    pip.className = 'vb-local-video-pip';
    pip.autoplay = true;
    pip.muted = true;
    pip.playsInline = true;
    pip.setAttribute('webkit-playsinline', '');
    stage.appendChild(pip);

    return { stage, dot, text, replay, avatar, img, defaultAvatarUrl: avatarUrl, speakingAvatarUrl: options.speakingAvatarUrl, avatarConfigured: Boolean(options.avatarUrl), dhTitle, dhBadge, widgetSlot, pip };
}

export function buildShell(container, options = {}) {
    container.classList.add('vb-root');
    if (!container.style.position) container.style.position = 'relative';
    if (!container.style.overflow) container.style.overflow = 'hidden';

    const consent = consentOverlay();
    const consentBtn = consent.querySelector('.vb-consent-primary');

    const phone = el('div', options.chatHome ? 'vb-phone vb-chat-home' : 'vb-phone');
    phone.style.height = '100%';
    phone.style.width = '100%';

    // 头部
    const header = el('div', 'vb-header');
    const brand = el('div', 'vb-mobile-brand');
    const brandIcon = el('span', 'vb-mobile-brand-icon');
    brandIcon.appendChild(icon('bank', 22));
    const brandText = el('div');
    brandText.appendChild(el('strong', null, { text: '数智化远程视频银行' }));
    brandText.appendChild(el('small', null, { text: '专业服务 · 温暖相伴' }));
    brand.append(brandIcon, brandText);
    const badge = el('span', 'vb-mobile-header-badge', { text: '演示' });
    const recordIndicator = el('span', null, { id: 'vb-record-indicator', style: 'display:none;' });
    const roomStatus = el('span', null, { id: 'vb-room-status', hidden: true });
    header.append(brand, badge, recordIndicator, roomStatus);
    const hangupBtn = el('button', 'vb-customer-hangup', { type: 'button', hidden: true, text: '挂断视频' });
    header.appendChild(hangupBtn);
    phone.appendChild(header);

    // 主舞台
    const stageRefs = stageArea(options);
    phone.appendChild(stageRefs.stage);
    // 独立控件悬浮在 SDK 容器上，不参与聊天的流式布局。
    stageRefs.widgetSlot.style.cssText = 'position:absolute;inset:0;display:none;z-index:100;pointer-events:none;';
    phone.appendChild(stageRefs.widgetSlot);
    // 数字人不属于业务舞台本身：它悬浮在页面内容之上，人工视频接入时再由接管逻辑隐藏。

    // 流程进度图（按需弹出，办结自动收起）
    const stepper = el('div', 'vb-stepper-container');
    stepper.style.display = 'none';
    phone.appendChild(stepper);

    // 等待人工接入面板
    const waitPanel = el('section', 'vb-human-wait-panel', { hidden: true });
    const waitTitle = el('strong', null, { text: '正在等待坐席接入' });
    waitPanel.appendChild(waitTitle);
    const waitResume = el('button', 'vb-wait-tone-resume', { type: 'button', hidden: true, text: '点击播放等待音乐' });
    waitPanel.appendChild(waitResume);
    phone.appendChild(waitPanel);

    // 对话历史
    const chat = el('div', 'vb-chat-history');
    chat.setAttribute('role', 'log');
    chat.setAttribute('aria-live', 'polite');
    const welcome = el('div', options.chatHome ? 'vb-welcome-text' : 'vb-msg vb-ai', {
        text: options.chatHome
            ? '您好，我是晨曦。请告诉我您想办什么，也可以直接选择一项服务。'
            : '您好！我是您的专属金融数字顾问晨曦。请直接告诉我您需要办理什么业务，例如："我要给王总转账200万" 或 "查询账户余额"。',
    });
    const chatZone = el('section', 'vb-chat-zone', { 'aria-label': '远程银行对话' });
    const chips = el('div', options.chatHome ? 'vb-chat-service-actions' : 'vb-quick-chips-bar');
    let greetingButton;
    if (options.chatHome) {
        const message = el('div', 'vb-msg vb-ai vb-chat-opening', { 'aria-label': '晨曦的消息' });
        greetingButton = el('button', 'vb-chat-greeting', { type: 'button', 'aria-label': '朗读欢迎语' });
        greetingButton.appendChild(icon('volume', 17));
        for (const service of CHAT_HOME_SERVICES) {
            const button = el('button', 'vb-chat-service-link', {
                type: 'button',
                'data-prompt': service.prompt,
                text: service.title,
            });
            chips.appendChild(button);
        }
        message.append(welcome, chips, greetingButton);
        chat.appendChild(message);
    } else {
        chat.appendChild(welcome);
        DEFAULT_CHIPS.forEach(([emoji, label]) => chips.appendChild(chipButton(emoji, label)));
    }
    chatZone.append(chat, stageRefs.avatar);
    phone.appendChild(chatZone);
    if (!options.chatHome) phone.appendChild(chips);

    // 底部输入
    const controls = el('div', 'vb-controls', { id: 'vb-input-area' });
    const voiceBtn = el('button', 'vb-voice-btn', { type: 'button', title: '点击开启/结束语音交互', 'aria-label': '语音输入', id: 'vb-btn-voice-input' });
    voiceBtn.appendChild(icon('mic', 21));
    const input = el('input', null, { type: 'text', placeholder: '想办什么业务，告诉我吧', 'aria-label': '输入您的业务需求', id: 'vb-user-input' });
    const sendBtn = el('button', 'vb-btn-send', { type: 'button', 'aria-label': '发送消息' });
    sendBtn.appendChild(icon('arrow', 22));
    controls.append(voiceBtn, input, sendBtn);
    phone.appendChild(controls);

    // 当前客户节点业务弹框：保留原页面，仅在其上方展示办理窗口。
    const dialog = el('section', 'vb-mobile-node-dialog', { role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'vb-node-dialog-title', hidden: true });
    const sheet = el('article', 'vb-mobile-node-sheet');
    const dialogHeader = el('header', 'vb-mobile-node-dialog-header');
    const dialogTitle = el('strong', null, { id: 'vb-node-dialog-title', text: '业务信息' });
    const dialogClose = el('button', 'vb-mobile-node-dialog-close', {
        type: 'button',
        hidden: true,
        text: '×',
        'aria-label': '关闭办理结果'
    });
    dialogHeader.append(dialogTitle, dialogClose);
    sheet.appendChild(dialogHeader);
    const panel = el('section', 'vb-mobile-node-panel');
    panel.innerHTML = '<article class="vb-node-form-body vb-node-dialog-loading" aria-live="polite"><span class="vb-node-loading-dot" aria-hidden="true"></span><h3>正在准备办理页面</h3><p>正在读取当前流程节点，请稍候。</p></article>';
    sheet.appendChild(panel);
    dialog.appendChild(sheet);
    phone.appendChild(dialog);

    container.append(consent, phone);

    return {
        root: container,
        phone,
        consent,
        consentBtn,
        header,
        hangupBtn,
        recordIndicator,
        roomStatus,
        ...stageRefs,
        stepper,
        waitPanel,
        waitTitle,
        waitResume,
        chat,
        welcome,
        greetingButton,
        chips,
        controls,
        voiceBtn,
        input,
        sendBtn,
        dialog,
        dialogTitle,
        dialogClose,
        panel,
    };
}

/** 快捷问法气泡（inner text 走 textContent，天然免疫注入）。 */
export function chipButton(label, text) {
    const button = el('button', 'vb-chip-item', { type: 'button', text: text ? `${label} ${text}` : label });
    return button;
}

export default { buildShell, chipButton };
