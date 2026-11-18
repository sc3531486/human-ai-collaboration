/*
 * 录像分段台账（契约 §3.4 / §6.3）——app 层唯一组织「开段/封存/办结告警」的地方。
 *
 * 原先 startSegment/finishSegment/sealResults/sealMissing 混在 instance.js 编排层，
 * 红线口径（fileSize === 0 不得宣告存证成功、回执缺失如实另记）埋在调用点；
 * 抽出后台账与告警文案自洽在一处，instance 的办结收尾只消费 warnings()。
 *
 * 命令面走 core/video-widget.js 接缝的 record.start / record.seal；换厂商时
 * 本模块零改动（控件未接入时 ensure() 返回 null，开段如实跳过）。
 */
import { video, ensure as ensureWidget } from '../core/video-widget.js';

export function createRecording(options = {}) {
    const getProcessId = options.getProcessId || (() => '');
    const getBizNo = options.getBizNo || (() => '');
    const getNodeName = options.getNodeName || (() => '');
    const isDisposed = options.isDisposed || (() => false);

    let active = false;
    let index = 0;
    const results = [];   // 台账：{ segmentIndex, fileSize }
    const missing = [];   // 未取得封存回执的段（≠0 字节，≠成功）

    /** 开始一段录制；进行中/已销毁/控件未接入时如实跳过（返回 false）。 */
    async function start(segmentIndex, handlerType) {
        if (active || isDisposed()) return false;
        if (!(await ensureWidget())) return false;   // 控件未接入 ⇒ 无画面可采，如实跳过
        try {
            const result = await video.record.start({
                processId: getProcessId(),
                bizNo: getBizNo(),
                nodeName: getNodeName(),
                segmentIndex,
                handlerType,
            });
            if (result === null || result === false) return false;
            active = true;
            index = segmentIndex;
            return true;
        } catch (error) {
            // 开段被拒（控件在场但命令失败）：不静默跳过——计入 missing，办结如实告警。
            missing.push(segmentIndex);
            console.warn('[record] 第', segmentIndex, '段开段失败（不静默跳过）:', error);
            return false;
        }
    }

    /** 封存当前段：回执入台账；无回执/异常记入 missing（回执缺失 ≠ 0 字节 ≠ 成功）。 */
    async function finish(segmentIndex) {
        if (!active) return null;
        active = false;
        try {
            const sealed = await video.record.seal();
            if (sealed && typeof sealed === 'object') {
                results.push({ segmentIndex, fileSize: Number(sealed.fileSize) || 0 });
                if (sealed.fileSize === 0) {
                    console.warn('[record] 第', segmentIndex, '段录像未采集到画面（0 字节）：', sealed.message || '');
                }
                return sealed;
            }
            missing.push(segmentIndex);
            console.warn('[record] 第', segmentIndex, '段未取得封存回执，不做成功宣告');
            return null;
        } catch (error) {
            missing.push(segmentIndex);
            console.warn('[record] 封存失败:', error);
            return null;
        }
    }

    /** 办结告警文案（0 字节 / 回执缺失分开陈述）；台账干净时返回空数组。 */
    function warnings() {
        const lines = [];
        const empty = results.filter((row) => row && row.fileSize === 0);
        if (empty.length) {
            lines.push(`⚠️ 第 ${empty.map((row) => row.segmentIndex).join('、')} 段录像未采集到画面（服务端记录为 0 字节），本次双录存证不完整，已如实上报，请联系座席核查。`);
        }
        if (missing.length) {
            lines.push(`⚠️ 第 ${missing.join('、')} 段未取得封存回执（开段/封存失败、控件未接入或分片上传失败），未宣告存证成功，请联系座席核查。`);
        }
        return lines;
    }

    return {
        start,
        finish,
        warnings,
        get active() { return active; },
        get index() { return index; },
        get results() { return results; },
        get missing() { return missing; },
    };
}

export default createRecording;
