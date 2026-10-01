package demo

class LegacyAmbiguousController {

    // two indexed classes are named AmbiguousService under grails-app/services
    def ambiguousService

    def index() {
        ambiguousService.run()
    }
}
