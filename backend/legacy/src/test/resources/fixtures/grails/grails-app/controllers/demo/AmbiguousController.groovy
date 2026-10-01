package demo

class AmbiguousController {

    // two indexed classes are named AmbiguousService: the reference stays ambiguous
    def ambiguousService

    def index() {
        ambiguousService.run()
    }
}
