"use client";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { controller } from "./controller";
import "./overlay.css";
import { gradientStops, isDarkBackground, isGradientUrl, popupBlocksPage, } from "./parse";
const DESKTOP = 768;
function useViewport() {
    const [width, setWidth] = useState(() => typeof window === "undefined" ? DESKTOP : window.innerWidth);
    useEffect(() => {
        const update = () => setWidth(window.innerWidth);
        update();
        window.addEventListener("resize", update);
        return () => window.removeEventListener("resize", update);
    }, []);
    return { width, desktop: width >= DESKTOP };
}
function themeVars(content) {
    return {
        background: content.theme.background,
        color: content.theme.text,
        ["--popup-bg"]: content.theme.background,
        ["--popup-text"]: content.theme.text,
        ["--popup-radius"]: `${content.theme.radius}px`,
    };
}
function cardWidth(size, desktop, screenWidth) {
    switch (size) {
        case "sm":
            return desktop ? 320 : screenWidth * 0.72;
        case "lg":
            return desktop ? 520 : screenWidth * 0.92;
        case "iab_300x250":
            return desktop ? 300 : Math.min(300, screenWidth * 0.92);
        default:
            return desktop ? 400 : screenWidth * 0.84;
    }
}
function CloseButton({ content, dark, onClose, inset, }) {
    if (!content.close.show)
        return null;
    const left = content.close.position === "topLeft";
    return (_jsx("button", { type: "button", "aria-label": "Close", onClick: onClose, style: {
            position: "absolute",
            top: inset ? 0 : 0,
            left: left ? 0 : undefined,
            right: left ? undefined : 0,
            width: 40,
            height: 40,
            border: "none",
            background: "transparent",
            cursor: "pointer",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 2,
        }, children: _jsx("span", { style: {
                width: 24,
                height: 24,
                borderRadius: 12,
                background: dark ? "rgba(0,0,0,0.4)" : "rgba(255,255,255,0.8)",
                color: dark ? "#fff" : "#374151",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontSize: 16,
                lineHeight: 1,
            }, children: "\u00D7" }) }));
}
function PopupImageView({ image, tappable, onTap, placeholder, radius, }) {
    const body = isGradientUrl(image.url) ? (_jsx("div", { style: {
            width: "100%",
            height: "100%",
            background: `linear-gradient(135deg, ${gradientStops(image.url).join(", ")})`,
        } })) : (
    // eslint-disable-next-line @next/next/no-img-element
    _jsx("img", { src: image.url, alt: image.alt ?? "", style: { width: "100%", height: "100%", objectFit: "cover", background: `${placeholder}14` } }));
    const clipped = (_jsx("div", { style: { width: "100%", height: "100%", overflow: "hidden", borderRadius: radius }, children: body }));
    if (!tappable)
        return clipped;
    return (_jsx("button", { type: "button", onClick: onTap, style: {
            padding: 0,
            border: "none",
            background: "transparent",
            width: "100%",
            height: "100%",
            cursor: "pointer",
            display: "block",
        }, children: clipped }));
}
function Buttons({ buttons, content, compact, onButton, }) {
    if (!buttons.length)
        return null;
    return (_jsx("div", { className: compact ? undefined : "optikpi-actions", style: compact
            ? { marginTop: 0, display: "flex", flexDirection: "row", gap: 8 }
            : undefined, children: buttons.map((button, index) => {
            const primary = button.style === "primary";
            return (_jsx("button", { type: "button", onClick: () => onButton(index), style: {
                    padding: compact ? "6px 12px" : undefined,
                    borderRadius: compact ? 8 : undefined,
                    border: primary ? "none" : `1px solid ${content.theme.text}33`,
                    background: primary ? content.theme.primary : "transparent",
                    color: primary ? "#fff" : content.theme.text,
                    fontWeight: compact ? 600 : undefined,
                    fontSize: compact ? 12 : undefined,
                    cursor: "pointer",
                    whiteSpace: compact ? "nowrap" : undefined,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                }, children: button.label || "Button" }, index));
        }) }));
}
function TextBlock({ content, titleSize = 16, bodySize = 14, fluid = false, }) {
    return (_jsxs("div", { children: [content.title ? (_jsx("div", { className: "optikpi-title", style: { fontSize: fluid ? undefined : titleSize, fontWeight: 600, lineHeight: 1.375 }, children: content.title })) : null, content.body ? (_jsx("div", { className: "optikpi-copy", style: {
                    marginTop: content.title ? 6 : 0,
                    fontSize: fluid ? undefined : bodySize,
                    lineHeight: 1.625,
                    color: content.theme.text,
                    opacity: 0.8,
                }, children: content.body })) : null] }));
}
function OverlayScrim({ content, onOutside }) {
    return (_jsx("div", { onClick: content.close.tapOutsideToClose ? onOutside : undefined, style: {
            position: "absolute",
            inset: 0,
            background: `rgba(0,0,0,${content.theme.overlayOpacity})`,
        } }));
}
function PopupCard({ active, desktop, }) {
    const content = active.popup.content;
    const width = typeof window === "undefined" ? 1280 : window.innerWidth;
    const dark = isDarkBackground(content.theme.background);
    const onClose = () => controller.close(active, true);
    const onButton = (index) => controller.tapButton(active, index);
    const onImage = () => controller.tapImage(active);
    const image = content.image;
    if (content.layout === "banner") {
        const top = content.position !== "bottom";
        const iab = content.size === "iab_320x50";
        const bannerWidth = iab ? Math.min(320, width) : width;
        return (_jsx("div", { style: {
                position: "fixed",
                left: 0,
                right: 0,
                top: top ? 0 : undefined,
                bottom: top ? undefined : 0,
                zIndex: 10050,
                display: "flex",
                justifyContent: "center",
                pointerEvents: "none",
            }, children: _jsxs("div", { style: {
                    pointerEvents: "auto",
                    width: bannerWidth,
                    height: iab ? bannerWidth * (50 / 320) : undefined,
                    background: content.theme.background,
                    color: content.theme.text,
                    borderRadius: !desktop && !iab ? 0 : Math.min(content.theme.radius, 8),
                    boxShadow: "0 10px 15px -3px rgba(0,0,0,0.1), 0 4px 6px -4px rgba(0,0,0,0.1)",
                    padding: iab ? "0 12px" : "10px 12px",
                    display: "flex",
                    alignItems: "center",
                    gap: 12,
                }, children: [image ? (_jsx("div", { style: { width: iab ? 32 : 40, height: iab ? 32 : 40, flexShrink: 0 }, children: _jsx(PopupImageView, { image: image, tappable: Boolean(image.tapAction), onTap: onImage, placeholder: content.theme.text, radius: 6 }) })) : null, _jsxs("div", { style: { flex: 1, minWidth: 0 }, children: [content.title ? (_jsx("div", { style: { fontWeight: 600, fontSize: iab ? 12 : 14, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }, children: content.title })) : null, content.body && !iab ? (_jsx("div", { style: { fontSize: 12, opacity: 0.8, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }, children: content.body })) : null] }), content.buttons.length ? (_jsx("div", { style: { maxWidth: "45%" }, children: _jsx(Buttons, { buttons: content.buttons, content: content, compact: true, onButton: onButton }) })) : null, content.close.show ? (_jsx("button", { type: "button", "aria-label": "Close", onClick: onClose, style: { border: "none", background: "transparent", cursor: "pointer", color: content.theme.text, opacity: 0.7 }, children: "\u00D7" })) : null] }) }));
    }
    if (content.layout === "toast") {
        const bottom = content.position === "bottomRight";
        const toastWidth = desktop ? (content.size === "sm" ? 280 : 340) : width - 24;
        const showImage = Boolean(image) && content.size === "md";
        return (_jsxs("div", { style: {
                position: "fixed",
                right: 12,
                top: bottom ? undefined : desktop ? 12 : 8,
                bottom: bottom ? 12 : undefined,
                zIndex: 10050,
                width: toastWidth,
                background: content.theme.background,
                color: content.theme.text,
                borderRadius: Math.min(content.theme.radius, 12),
                boxShadow: "0 20px 25px -5px rgba(0,0,0,0.1)",
                overflow: "hidden",
                display: "flex",
            }, children: [showImage && image ? (_jsx("div", { style: { width: 64, flexShrink: 0 }, children: _jsx(PopupImageView, { image: image, tappable: Boolean(image.tapAction), onTap: onImage, placeholder: content.theme.text }) })) : null, _jsxs("div", { style: { padding: "12px 32px 12px 12px", flex: 1 }, children: [_jsx(TextBlock, { content: content, titleSize: 14, bodySize: 12 }), content.buttons.length ? (_jsx("div", { style: { marginTop: 8 }, children: _jsx(Buttons, { buttons: content.buttons, content: content, compact: true, onButton: onButton }) })) : null] }), _jsx(CloseButton, { content: content, dark: dark, onClose: onClose })] }));
    }
    if (content.layout === "bottomSheet") {
        return (_jsxs("div", { className: "optikpi-inapp optikpi-stage", children: [_jsx(OverlayScrim, { content: content, onOutside: onClose }), _jsxs("div", { className: "optikpi-sheet-panel optikpi-card", "data-size": content.size, style: { ...themeVars(content), overflow: "auto" }, children: [_jsx("div", { style: { width: 40, height: 4, borderRadius: 2, background: "#D1D5DB", margin: "8px auto 0" } }), image && content.size !== "sm" ? (_jsx("div", { className: "optikpi-hero", style: { height: desktop ? 180 : 160, margin: "12px 16px 0" }, children: _jsx(PopupImageView, { image: image, tappable: Boolean(image.tapAction), onTap: onImage, placeholder: content.theme.text, radius: 8 }) })) : null, _jsxs("div", { className: "optikpi-body", children: [_jsx(TextBlock, { content: content, fluid: true }), _jsx(Buttons, { buttons: content.buttons, content: content, onButton: onButton })] }), _jsx(CloseButton, { content: content, dark: dark, onClose: onClose })] })] }));
    }
    if (content.layout === "imageOnly") {
        const iab = content.size === "iab_300x250";
        const w = cardWidth(content.size, desktop, width);
        const ratio = iab ? 300 / 250 : 4 / 5;
        return (_jsxs("div", { style: { position: "fixed", inset: 0, zIndex: 10050 }, children: [_jsx(OverlayScrim, { content: content, onOutside: onClose }), _jsxs("div", { style: {
                        position: "absolute",
                        left: "50%",
                        top: "50%",
                        width: w,
                        height: w / ratio,
                        transform: "translate(-50%, -50%)",
                        borderRadius: content.theme.radius,
                        overflow: "hidden",
                        boxShadow: "0 25px 50px -12px rgba(0,0,0,0.25)",
                    }, children: [image ? (_jsx(PopupImageView, { image: image, tappable: true, onTap: onImage, placeholder: content.theme.text })) : null, _jsx(CloseButton, { content: content, dark: true, onClose: onClose })] })] }));
    }
    const iab = content.size === "iab_300x250";
    const buttons = iab ? content.buttons.slice(0, 1) : content.buttons;
    return (_jsxs("div", { className: "optikpi-inapp optikpi-stage", children: [_jsx(OverlayScrim, { content: content, onOutside: onClose }), _jsxs("div", { className: "optikpi-modal optikpi-card", "data-size": content.size, style: themeVars(content), children: [image && !iab ? (_jsx("div", { className: "optikpi-hero", children: _jsx(PopupImageView, { image: image, tappable: Boolean(image.tapAction), onTap: onImage, placeholder: content.theme.text }) })) : null, _jsxs("div", { className: "optikpi-body", style: iab ? { padding: 16 } : undefined, children: [_jsx(TextBlock, { content: content, fluid: true }), _jsx(Buttons, { buttons: buttons, content: content, onButton: onButton })] }), _jsx(CloseButton, { content: content, dark: dark || Boolean(image), onClose: onClose })] })] }));
}
function subscribeActive(onChange) {
    return controller.subscribe(onChange);
}
function currentActive() {
    return controller.current;
}
function serverActive() {
    return null;
}
export default function OptikpiInAppOverlay() {
    const active = useSyncExternalStore(subscribeActive, currentActive, serverActive);
    const { width, desktop } = useViewport();
    useEffect(() => {
        if (!active)
            return;
        controller.didDraw(active);
        const seconds = active.popup.content.autoDismissSec;
        if (!seconds)
            return;
        const timer = window.setTimeout(() => controller.close(active, true), seconds * 1000);
        return () => window.clearTimeout(timer);
    }, [active]);
    useEffect(() => {
        if (!active || !popupBlocksPage(active.popup.content.layout))
            return;
        const previous = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        const onKey = (event) => {
            if (event.key === "Escape")
                controller.close(active, true);
        };
        window.addEventListener("keydown", onKey);
        return () => {
            document.body.style.overflow = previous;
            window.removeEventListener("keydown", onKey);
        };
    }, [active]);
    const card = useMemo(() => {
        if (!active)
            return null;
        return _jsx(PopupCard, { active: active, desktop: desktop });
    }, [active, desktop, width]);
    return card;
}
export { OptikpiInAppOverlay };
//# sourceMappingURL=overlay.js.map