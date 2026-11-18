import pico from './pico.js';
import cascadeUrl from './facefinder.bin?url&inline';

let classify;

export function createFaceFraming() {
    if (!classify) {
        const binary = atob(cascadeUrl.slice(cascadeUrl.indexOf(',') + 1));
        classify = pico.unpack_cascade(Int8Array.from(binary, (byte) => byte.charCodeAt(0)));
    }
    const canvas = document.createElement('canvas');
    canvas.width = 320;
    canvas.height = 240;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    const pixels = new Uint8Array(canvas.width * canvas.height);
    const image = { pixels, nrows: canvas.height, ncols: canvas.width, ldim: canvas.width };

    return (video, frame) => {
        context.drawImage(video, ...frame, 0, 0, canvas.width, canvas.height);
        const colors = context.getImageData(0, 0, canvas.width, canvas.height).data;
        for (let index = 0; index < pixels.length; index += 1) {
            const offset = index * 4;
            pixels[index] = (colors[offset] * 77 + colors[offset + 1] * 150 + colors[offset + 2] * 29) >> 8;
        }
        const matches = pico.run_cascade(image, classify, {
            shiftfactor: 0.1,
            minsize: 70,
            maxsize: 185,
            scalefactor: 1.15,
        });
        const faces = pico.cluster_detections(matches, 0.2).filter((match) => match[3] > 35);
        if (faces.length !== 1) return null;
        const [row, column, size] = faces[0];
        const y = row / canvas.height;
        const x = column / canvas.width;
        const scale = size / canvas.height;
        return Math.abs(x - 0.5) <= 0.11 && Math.abs(y - 0.5) <= 0.12 && scale >= 0.32 && scale <= 0.71
            ? { x, y, scale }
            : null;
    };
}
