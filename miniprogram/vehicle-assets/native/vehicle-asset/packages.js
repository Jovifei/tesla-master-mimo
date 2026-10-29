// Native require.async is retained in this module scope.
module.exports = {
  'vehicle-assets-1': function () { return require.async('../../vehicle-assets-1/ready.js') },
  'vehicle-assets-2': function () { return require.async('../../vehicle-assets-2/ready.js') },
  'vehicle-assets-3': function () { return require.async('../../vehicle-assets-3/ready.js') },
  'vehicle-assets-4': function () { return require.async('../../vehicle-assets-4/ready.js') },
  'vehicle-assets-5': function () { return require.async('../../vehicle-assets-5/ready.js') },
  'vehicle-assets-6': function () { return require.async('../../vehicle-assets-6/ready.js') }
}
