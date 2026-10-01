package demo

class UrlMappings {

    static mappings = {
        // explicit controller and action
        "/customer/$id"(controller: 'customer', action: 'show')
        // Grails default action "index" is a documented convention
        "/customer"(controller: 'customer')
        // RESTful resource convention; the action set is not enumerated statically
        "/books"(resources: 'book')
        // dynamic controller/action expressions cannot be resolved statically
        "/legacy/$controller/$action?/$id?"(controller: "$controller", action: "$action")
        group("/api") {
            "/ping"(controller: 'customer', action: 'ping')
        }
    }
}
