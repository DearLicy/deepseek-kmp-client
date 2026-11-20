import SwiftUI

enum DSColor {
    static let primary = Color(hex: 0x426EFE)
    static let primaryAction = Color(hex: 0x4C6DF5)
    static let primarySoft = Color(hex: 0xEEF3FD)
    static let background = Color.white
    static let surfaceMuted = Color(hex: 0xF8F8F8)
    static let textPrimary = Color(hex: 0x0F0F0F)
    static let textSecondary = Color(hex: 0x7D7F84)
    static let border = Color(hex: 0xECEDF0)
    static let danger = Color(hex: 0xFF3B30)
    static let success = Color(hex: 0x34C759)
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xff) / 255,
                  green: Double((hex >> 8) & 0xff) / 255,
                  blue: Double(hex & 0xff) / 255, opacity: 1)
    }
}
