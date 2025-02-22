(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        define('kotlin', ['exports'], factory);
    }
    else if (typeof module === 'object' && typeof module.exports === 'object') {
        factory(module.exports);
    }
    else {
        root.kotlin = {};
        factory(root.kotlin);
    }
}(this, function (Kotlin) {
    var _ = Kotlin;

    insertContent();
}));
