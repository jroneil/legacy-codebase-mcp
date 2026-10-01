class UrlMappings {

    static excludes = ["/images/*", "/css/*", "/js/*"]

    static mappings = {
        // Grails 2.x location: grails-app/conf/UrlMappings.groovy
        "/customer/$id"(controller: "customer", action: "show")
        // Grails 2.x named URL mapping (removed in Grails 3)
        name customerList: "/customers"(controller: "customer", action: "list")
        // default action is a Grails convention
        "/customer"(controller: "customer")
        // nested per-mapping constraint closure: the inner DSL call is not a route
        "/product/$id?"(controller: "customer", action: "show") {
            id matches: /\d+/
        }
        // legacy closure action: declared in source but not a method
        "/legacy/list"(controller: "legacyClosure", action: "list")
        // status-code mapping without a controller
        "500"(view: "/error")
        // dynamic controller/action expressions stay unresolved
        "/$controller/$action?/$id?"(controller: "$controller", action: "$action")
    }
}
