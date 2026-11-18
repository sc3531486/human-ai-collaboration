import { escape } from '../../../core/format.js';

const HELP = {
    MEDIA: '把银行卡放入取景框，确认卡片四角完整后点拍照。',
    ID_FRONT: '请对准带有姓名和人像的一面，确认清晰后点拍照。',
    ID_BACK: '请翻到国徽面，确认有效期清晰后点拍照。',
    FACE: '请正视镜头，把脸移入框内并保持不动。对准后将自动拍照。',
};

function crop(video) {
    const width = Math.min(video.videoWidth, video.videoHeight * 4 / 3);
    const height = width * 3 / 4;
    return [(video.videoWidth - width) / 2, (video.videoHeight - height) / 2, width, height];
}

function cardPicker(field, cards, selected) {
    if (!field) return '';
    if (!cards.length) return '<p class="vb-node-form-error">没有可办理的银行卡。请重新读取当前业务。</p>';
    return `<label class="vb-node-field">办理银行卡
        <select class="vb-camera-card">
            ${cards.map((card) => `<option value="${escape(card.id)}" ${card.id === selected ? 'selected' : ''}>${escape(card.label || card.cardNoMasked)}</option>`).join('')}
        </select>
    </label>`;
}

export default {
    mount(panel, state, ctx) {
        const policy = state.nodePolicy || {};
        const fields = policy.fields || [];
        const photo = fields.find((field) => field.type === 'photo');
        if (ctx.readOnly || !photo || !policy.actors?.includes('CUSTOMER')) return false;

        const face = photo.evidenceKind === 'FACE';
        const card = fields.find((field) => field.type === 'card');
        const cards = ctx.values.cards || [];
        panel.innerHTML = `<article class="vb-mobile-node-form vb-camera-form">
            <div class="vb-node-form-body">
                ${cardPicker(card, cards, card && ctx.values[card.name])}
                <p class="vb-camera-help">${escape(HELP[photo.evidenceKind] || photo.label)}</p>
                <div class="vb-camera-preview${face ? ' vb-camera-face' : ''}">
                    <video muted autoplay playsinline aria-label="摄像头预览"></video>
                    <canvas hidden aria-label="已拍摄照片"></canvas>
                    <div class="vb-camera-guide" aria-hidden="true"></div>
                </div>
                <p class="vb-camera-status" role="status">正在启动摄像头…</p>
            </div>
            <footer class="vb-node-form-footer">
                <button class="vb-node-primary" type="button" hidden></button>
                <p class="vb-node-form-error" role="alert"></p>
            </footer>
        </article>`;
        const video = panel.querySelector('video');
        const canvas = panel.querySelector('canvas');
        const guide = panel.querySelector('.vb-camera-guide');
        const status = panel.querySelector('.vb-camera-status');
        const button = panel.querySelector('button');
        const error = panel.querySelector('[role="alert"]');
        const select = panel.querySelector('select');

        let disposed = false;
        let stream;
        let scanTimer;
        let busy = false;
        let upload;
        let snapshot;
        let selectedCard;

        function setAction(label, action) {
            button.hidden = !label;
            button.textContent = label;
            button.onclick = action || null;
        }

        function stopCamera() {
            clearTimeout(scanTimer);
            stream?.getTracks().forEach((track) => track.stop());
            stream = null;
            video.srcObject = null;
        }

        function report(problem, label, action) {
            if (disposed) return;
            error.textContent = problem.message;
            setAction(label, action);
        }

        async function submitPhoto() {
            if (busy || disposed) return;
            busy = true;
            error.textContent = '';
            setAction('');
            try {
                status.textContent = '正在上传照片…';
                if (!upload) upload = await ctx.photoUpload(photo.name, snapshot);
                if (disposed) return;
                status.textContent = face ? '照片已保存，正在核验身份…' : '照片已保存，正在进入下一步…';
                const values = { [photo.name]: upload.fileUrl, evidenceReceipts: { [photo.name]: upload.eventId } };
                if (card) values[card.name] = selectedCard;
                await ctx.submit(values, policy.action);
            } catch (problem) {
                status.textContent = '照片已保留，可以重试。';
                report(problem, '重试提交', submitPhoto);
            } finally {
                busy = false;
            }
        }

        async function capture() {
            if (busy || disposed || !stream || !video.videoWidth) return;
            busy = true;
            setAction('');
            selectedCard = select?.value;
            if (select) select.disabled = true;
            canvas.width = 960;
            canvas.height = 720;
            try {
                canvas.getContext('2d').drawImage(video, ...crop(video), 0, 0, canvas.width, canvas.height);
                canvas.hidden = false;
                guide.hidden = true;
                stopCamera();
                snapshot = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
                if (!snapshot) throw new Error('照片生成失败，请重新拍摄。');
            } catch (problem) {
                report(problem, '重新拍摄', startCamera);
            } finally {
                busy = false;
            }
            if (snapshot) await submitPhoto();
        }

        function watchFace(detect) {
            let previous;
            let steadySince;
            const scan = () => {
                if (disposed || !stream || busy) return;
                let position;
                try {
                    position = video.readyState >= 2 && !document.hidden ? detect(video, crop(video)) : null;
                } catch (problem) {
                    stopCamera();
                    status.textContent = '人脸取景中断。';
                    report(problem, '重试开启摄像头', startCamera);
                    return;
                }
                const steady = position && previous
                    && Math.abs(position.x - previous.x) < 0.035
                    && Math.abs(position.y - previous.y) < 0.04
                    && Math.abs(position.scale - previous.scale) < 0.09;
                const now = performance.now();
                steadySince = steady ? steadySince : now;
                previous = position;
                guide.classList.toggle('vb-camera-aligned', Boolean(position));
                const hint = position ? '已对准，请保持不动…' : '请把脸移到框内并正视摄像头。';
                if (status.textContent !== hint) status.textContent = hint;
                if (steady && now - steadySince >= 700) {
                    void capture();
                } else {
                    scanTimer = setTimeout(scan, 240);
                }
            };
            scan();
        }

        async function startCamera() {
            if (disposed || busy || stream) return;
            if (card && !select) {
                status.textContent = '';
                setAction('重新读取', ctx.rematch);
                return;
            }
            busy = true;
            status.textContent = '正在启动摄像头…';
            error.textContent = '';
            canvas.hidden = true;
            guide.hidden = false;
            guide.classList.remove('vb-camera-aligned');
            setAction('');
            try {
                await ctx.authorizeCapture?.();
                if (disposed) return;
                if (!navigator.mediaDevices?.getUserMedia) throw new Error('请使用支持摄像头的浏览器，并通过当前 HTTPS 地址访问。');
                const camera = await navigator.mediaDevices.getUserMedia({
                    video: { facingMode: { ideal: face ? 'user' : 'environment' }, width: { ideal: 1280 }, height: { ideal: 960 } },
                    audio: false,
                });
                if (disposed) {
                    camera.getTracks().forEach((track) => track.stop());
                    return;
                }
                stream = camera;
                video.srcObject = stream;
                await video.play();
                let detect;
                if (face) {
                    status.textContent = '正在准备人脸取景…';
                    const { createFaceFraming } = await import('./face-detector/framing.js');
                    detect = createFaceFraming();
                }
                busy = false;
                if (disposed || !stream) return;
                if (select) select.disabled = false;
                stream.getVideoTracks().forEach((track) => {
                    track.onended = () => {
                        stopCamera();
                        status.textContent = '摄像头已断开。';
                        setAction('重新开启摄像头', startCamera);
                    };
                });
                if (detect) watchFace(detect);
                else {
                    status.textContent = '摄像头已就绪。';
                    setAction('拍照', () => { ctx.primeAudio(); void capture(); });
                }
            } catch (problem) {
                stopCamera();
                status.textContent = '摄像头未就绪。';
                const message = problem.name === 'NotAllowedError' ? '请允许浏览器访问摄像头，然后重试。' : problem.message;
                report(new Error(message), '重试开启摄像头', startCamera);
            } finally {
                busy = false;
            }
        }

        void startCamera();
        return () => {
            disposed = true;
            stopCamera();
        };
    },
};
