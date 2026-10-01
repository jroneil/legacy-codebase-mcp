package demo

class LegacyClosureController {

    // Grails 2.x closure action: an explicit source declaration, but not a method.
    def list = {
        render(view: "list")
    }
}
