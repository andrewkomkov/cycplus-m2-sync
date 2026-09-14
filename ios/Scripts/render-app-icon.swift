#!/usr/bin/env swift
// Рисует иконку приложения: белый велосипед на зелёном градиенте, плюс тёмный и тонированный
// варианты для iOS 18+. SF Symbols в иконках запрещены лицензией, поэтому геометрия своя.
//
//   swift ios/Scripts/render-app-icon.swift ios/M2Sync/Resources/Assets.xcassets/AppIcon.appiconset

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let size = 1024
let output = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? ".", isDirectory: true)

struct Variant {
    let file: String
    let top: CGColor
    let bottom: CGColor
    let glyph: CGColor
}

func rgb(_ hex: UInt32) -> CGColor {
    CGColor(
        srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
        green: CGFloat((hex >> 8) & 0xFF) / 255,
        blue: CGFloat(hex & 0xFF) / 255,
        alpha: 1
    )
}

let variants = [
    Variant(file: "AppIcon.png", top: rgb(0x3DDC6F), bottom: rgb(0x1F9E4B), glyph: rgb(0xFFFFFF)),
    Variant(file: "AppIcon-Dark.png", top: rgb(0x2C2C2E), bottom: rgb(0x111112), glyph: rgb(0x34C759)),
    // Тонированный вариант система перекрашивает сама: нужен светлый знак на чёрном.
    Variant(file: "AppIcon-Tinted.png", top: rgb(0x000000), bottom: rgb(0x000000), glyph: rgb(0xFFFFFF)),
]

func drawBicycle(in context: CGContext, color: CGColor) {
    // Дальше координаты с началом в левом верхнем углу.
    context.translateBy(x: 0, y: CGFloat(size))
    context.scaleBy(x: 1, y: -1)

    // Велосипед по высоте занимает 282…740 — поднимаем, чтобы центр пришёлся на середину.
    let lift: CGFloat = 40
    func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y - lift) }

    let rear = p(300, 610)
    let front = p(724, 610)
    let crank = p(500, 610)
    let seat = p(452, 392)
    let head = p(680, 392)
    let wheelRadius: CGFloat = 170

    context.setStrokeColor(color)
    context.setFillColor(color)
    context.setLineCap(.round)
    context.setLineJoin(.round)

    context.setLineWidth(46)
    for hub in [rear, front] {
        context.strokeEllipse(in: CGRect(
            x: hub.x - wheelRadius,
            y: hub.y - wheelRadius,
            width: wheelRadius * 2,
            height: wheelRadius * 2
        ))
    }

    context.setLineWidth(42)
    let frame = CGMutablePath()
    frame.move(to: rear)
    frame.addLine(to: crank)
    frame.addLine(to: seat)
    frame.closeSubpath()
    frame.move(to: seat)
    frame.addLine(to: head)
    frame.addLine(to: crank)
    frame.move(to: head)
    frame.addLine(to: front)
    // Руль: вынос вверх и назад, затем руль вперёд.
    frame.move(to: head)
    frame.addLine(to: p(652, 322))
    frame.addLine(to: p(744, 322))
    // Седло.
    frame.move(to: seat)
    frame.addLine(to: p(440, 350))
    frame.move(to: p(392, 350))
    frame.addLine(to: p(500, 350))
    context.addPath(frame)
    context.strokePath()

    for (hub, radius) in [(rear, CGFloat(22)), (front, CGFloat(22)), (crank, CGFloat(34))] {
        context.fillEllipse(in: CGRect(x: hub.x - radius, y: hub.y - radius, width: radius * 2, height: radius * 2))
    }
}

let space = CGColorSpace(name: CGColorSpace.sRGB)!
for variant in variants {
    // Без альфа-канала: App Store не принимает прозрачную основную иконку.
    guard let context = CGContext(
        data: nil,
        width: size,
        height: size,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: space,
        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
    ) else { fatalError("no context") }

    let gradient = CGGradient(colorsSpace: space, colors: [variant.top, variant.bottom] as CFArray, locations: [0, 1])!
    context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: size), end: .zero, options: [])
    drawBicycle(in: context, color: variant.glyph)

    let url = output.appendingPathComponent(variant.file)
    guard let image = context.makeImage(),
          let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)
    else { fatalError("cannot write \(url.path)") }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("cannot write \(url.path)") }
    print("wrote \(url.lastPathComponent)")
}
