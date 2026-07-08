import Foundation

enum UrlSecurity {
    static func validate(_ urlString: String, token: String?) -> String? {
        guard let url = URL(string: urlString), let host = url.host else {
            return "Invalid URL format"
        }
        if token != nil && !token!.isEmpty && url.scheme == "http" {
            let isLocal = host == "localhost"
                || host.hasPrefix("127.")
                || host.hasPrefix("192.168.")
                || host.hasPrefix("10.")
                || host.hasSuffix(".local")
            if !isLocal {
                return "HTTP is not allowed for remote servers. Use HTTPS to protect your token."
            }
        }
        return nil
    }
}
