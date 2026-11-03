import AppKit

let output = CommandLine.arguments[1]
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
NSColor.clear.setFill()
NSRect(x: 0, y: 0, width: 1024, height: 1024).fill()
let tile = NSBezierPath(roundedRect: NSRect(x: 76, y: 76, width: 872, height: 872), xRadius: 194, yRadius: 194)
NSGraphicsContext.saveGraphicsState()
let shadow = NSShadow()
shadow.shadowColor = NSColor.black.withAlphaComponent(0.22)
shadow.shadowBlurRadius = 28
shadow.shadowOffset = NSSize(width: 0, height: -10)
shadow.set()
NSColor(calibratedRed: 0.02, green: 0.25, blue: 0.68, alpha: 1).setFill()
tile.fill()
NSGraphicsContext.restoreGraphicsState()
NSGradient(starting: NSColor(calibratedRed: 0.05, green: 0.52, blue: 1, alpha: 1), ending: NSColor(calibratedRed: 0.01, green: 0.22, blue: 0.77, alpha: 1))!.draw(in: tile, angle: -70)
NSColor.white.withAlphaComponent(0.2).setStroke()
tile.lineWidth = 2
tile.stroke()
// Three database tiers, constructed as cylinders for a legible small-size silhouette.
for y: CGFloat in [350, 482, 614] {
    let body = NSBezierPath()
    body.move(to: NSPoint(x: 340, y: y + 64))
    body.line(to: NSPoint(x: 340, y: y))
    body.curve(to: NSPoint(x: 684, y: y), controlPoint1: NSPoint(x: 340, y: y - 82), controlPoint2: NSPoint(x: 684, y: y - 82))
    body.line(to: NSPoint(x: 684, y: y + 64))
    body.close()
    NSColor(calibratedWhite: 0.92, alpha: 1).setFill()
    body.fill()
    NSColor.white.setFill()
    NSBezierPath(ovalIn: NSRect(x: 340, y: y + 12, width: 344, height: 104)).fill()
}
// Code brackets tie the database mark to the query workspace.
NSColor.white.withAlphaComponent(0.9).setStroke()
for left in [true, false] {
    let p = NSBezierPath()
    let outer: CGFloat = left ? 222 : 802
    let inner: CGFloat = left ? 272 : 752
    p.move(to: NSPoint(x: inner, y: 620))
    p.line(to: NSPoint(x: outer, y: 620))
    p.line(to: NSPoint(x: outer, y: 404))
    p.line(to: NSPoint(x: inner, y: 404))
    p.lineWidth = 24
    p.lineCapStyle = .round
    p.lineJoinStyle = .round
    p.stroke()
}
NSGraphicsContext.restoreGraphicsState()
try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: output))
