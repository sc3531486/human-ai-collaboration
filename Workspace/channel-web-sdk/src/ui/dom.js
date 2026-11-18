/*
 * 原生 DOM 构建工具（契约 §8.1：SDK 只用原生 DOM，禁 Vue/Element Plus）。
 * 约定：场景组件一律把用户可控字符串经 escape() 后再进入 textContent 或 innerHTML。
 */

/** 创建元素：attrs 里 className / text / html / dataset / on* 均为语法糖。 */
export function el(tag, attrs = {}, children = []) {
    const node = document.createElement(tag);
    applyAttrs(node, attrs);
    append(node, children);
    return node;
}

/** 创建 SVG 片段（静态 path 表专用）。 */
export function svg(markup, attrs = {}) {
    const wrapper = document.createElement('div');
    wrapper.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg">${markup}</svg>`;
    const node = wrapper.firstElementChild;
    Object.keys(attrs).forEach((key) => node.setAttribute(key, attrs[key]));
    return node;
}

export function applyAttrs(node, attrs = {}) {
    Object.keys(attrs || {}).forEach((key) => {
        const value = attrs[key];
        if (value === null || value === undefined || value === false) return;
        if (key === 'className') node.setAttribute('class', value);
        else if (key === 'text') node.textContent = value;
        else if (key === 'html') node.innerHTML = value;
        else if (key === 'style' && typeof value === 'object') Object.assign(node.style, value);
        else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
        else if (value === true) node.setAttribute(key, '');
        else node.setAttribute(key, String(value));
    });
    return node;
}

export function append(node, children) {
    const list = Array.isArray(children) ? children : [children];
    list.forEach((child) => {
        if (child === null || child === undefined || child === false) return;
        node.appendChild(typeof child === 'string' || typeof child === 'number'
            ? document.createTextNode(String(child))
            : child);
    });
    return node;
}

export function clear(node) {
    while (node && node.firstChild) node.removeChild(node.firstChild);
    return node;
}

/** 节点内查询（不用全局 document.querySelector，避免选择器泄漏）。 */
export const find = (root, selector) => (root ? root.querySelector(selector) : null);
export const findAll = (root, selector) => (root ? Array.from(root.querySelectorAll(selector)) : []);

/** classList 语法糖：toggle 省略强转。 */
export const cls = (node, name, on) => Boolean(node) && node.classList.toggle(name, Boolean(on));

export default { el, svg, applyAttrs, append, clear, find, findAll, cls };
