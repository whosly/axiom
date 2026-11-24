import { MessagePlugin, DialogPlugin } from 'tdesign-react';

/**
 * 统一用户反馈入口 / Unified user-feedback helpers.
 *
 * <p>替代阻塞式 alert/confirm：MessagePlugin 顶部滑入、自动消失、不阻塞交互、
 * 随主题变色；confirm 改为 Promise 化的应用内对话框。
 * / Replaces blocking alert/confirm with non-blocking toasts and a Promise-based
 * in-app confirmation dialog, consistent with the design system.
 */

/** 错误轻提示 / Error toast. */
export function notifyError(msg: string): void {
  void MessagePlugin.error({ content: msg, duration: 4000 });
}

/** 成功轻提示 / Success toast. */
export function notifySuccess(msg: string): void {
  void MessagePlugin.success({ content: msg, duration: 3000 });
}

/** 信息轻提示 / Info toast. */
export function notifyInfo(msg: string): void {
  void MessagePlugin.info({ content: msg, duration: 3000 });
}

/**
 * 确认对话框（Promise 化）/ Promise-based confirmation dialog.
 *
 * @param header 标题 / Title.
 * @param body   正文 / Body text.
 * @returns 用户是否确认 / Whether the user confirmed.
 */
export function confirmDialog(header: string, body: string): Promise<boolean> {
  return new Promise(resolve => {
    const dialog = DialogPlugin.confirm({
      header,
      body,
      confirmBtn: { content: '确定', theme: 'danger' },
      cancelBtn: '取消',
      onClose: () => { resolve(false); dialog.destroy(); },
      onConfirm: () => { resolve(true); dialog.destroy(); },
    });
  });
}
