package demo

class LegacyController {

    // conventional name, but no matching service is indexed
    def missingService

    def index() {
        render view: 'index'
    }
}
