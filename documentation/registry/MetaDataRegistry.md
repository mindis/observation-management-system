# Meta-Data Registry

The purpose of this module is to provide a fast lookup onto the catalogue/triple-store held meta-data regarding the features, procedures, timeseries, and QC checks used within the processing framework.  Thus it is a writable store, periodically updated by harvesting the meta-data repositories as and when necessary for the stream processing.

The data is collated here as it is possible to run instances of the registry on each of the data-processing nodes, allowing for faster response times than if the processing nodes were to continually generate HTTP requests for the data.  The use of generated keys also makes the lookup simpler than the multiple SPARQL queries that would be necessary otherwise.  Caching the information on the processing nodes is another potential direction to be looked into after the initial investigation completes.

## Basic Meta-Data

### Summary Information

The term basic in this context means (generally) unchaning, uncomplicated meta-data, such as the core details of particular feature, procedure, observed property unique sets.  As data is harvested from the sensor network logger generated files, it is represented by the site and sensor ID values found in the logger files.  For instance the site is usually a code such as `SBAS` or `MOO`, and the sensor ID values are a shortened version of the full name, like `PRT1` standing for the first Platinum Resistance Thermometer on a logger.

At some point it is necessary to perform semantic mediation between these basic representations and the catalogue versions, and the registry is used for this.  The key is created from the meta-data representation provided by the loggers, and the value is the URI of the catalogue concept.

For example the following describes a Platinum Resistance Thermometer and a Nephelometer.  The URI's are dummy placeholders for example only, until the next part of the development updates this.

```
MOO::PRT1::feature http://placeholder.catalogue.ceh.ac.uk/example/moosite/info
MOO::PRT1::procedure http://placeholder.catalogue.ceh.ac.uk/example/moosite/procedures/prt/one
MOO::PRT1::observableproperty http://placeholder.catalogue.ceh.ac.uk/example/phenomena/temperature

MOO::NEPH::feature http://placeholder.catalogue.ceh.ac.uk/example/moosite/info
MOO::NEPH::procedure http://placeholder.catalogue.ceh.ac.uk/example/moosite/procedures/nephelometer
MOO::NEPH::observableproperty http://placeholder.catalogue.ceh.ac.uk/example/phenomena/turbidity
```
The values returned by the above become the initial part of every key that follows, taking the format:
```
feature::procedure::observedproperty
```
This creates a triple referred to as the procedure UID (PUID), and referenced in the examples below as `feat::proc::obsprop`.

### Timeseries Information

For each stream of data generated by a procedure, there is meta-data associated with the timeseries as described in section LINK TO WML DISCUSSION.  Part of this information, the `
intended observation spacing`, is held in the registry to provide a threshold for a QC check.  This QC check ensures that the correct intended spacing between observations is adhered to, and is looked up using the PUID with the `intendedspacing` postfix, and returns the number of minutes as a value.  The representation is not easy to manually read, however this is designed for machine lookup only, with time represented in milliseconds.

```
http://placeholder.catalogue.ceh.ac.uk/example/moosite/info::http://placeholder.catalogue.ceh.ac.uk/example/moosite/procedures/prt/one::http://placeholder.catalogue.ceh.ac.uk/example/phenomena/temperature::intendedspacing 240000
```

### Platform Checks

Maintenance can be carried out on the recording platform, causing some readings to be altered as a direct consequence.  It is also possible that cleaning appointments can be missed, and so the readings may deteriorate from the ground truth until the next cleaning event.  Battery voltage can also fall below an acceptable level, causing inconsistent readings.  Other issues with the recording platform/system as a whole may be recorded.  When such events occur, it is necessary to know what sensors they affect.  The register contains a list of the types of issue a feature can have (as the feature generally refers to the recording platform the sensors are located on), and the procedures affected by this.  The example below shows entries for the feature `LEVE` in the following format:

```
LEVE::meta::identity notcleaned::maintenance

LEVE::meta::identity::notcleaned feat,proc,obsprop::feat,proc,,obsprop::feat,proc,obsprop
LEVE::meta::identity::maintenance feat,proc,obsprop::feat,proc,obsprop::feat,proc,obsprop

LEVE::meta::value battery
LEVE::meta::value::battery feat,proc,obsprop::feat,proc,obsprop::feat,proc,obsprop
LEVE::meta::value::battery::threshold single
LEVE::meta::value::battery::min 10.5
LEVE::meta::value::battery::max 100

```

The values `identity` and `value` are used to distinguish between two types of metadata information.  `identity` based entries are those that by their existence cause QC checks to fail, as they signal events such as maintenance being carried out.  `value` based entries are those that an observation is taken relating to a system or phenomena that effects the stations or sensors ability to perform, such as the battery voltage reading.  If such a value is outwith an operating range, this causes QC checks to fail.

The key `LEVE::meta::identity` relates to a value of the differing checks, which will be represented by their URI once implemented.  The key `LEVE::meta::identity::notcleaned` relates to a set of three value CSV entries, separated by `::`, which list the feature, procedure, observable property sets affected by this metadata check.  The URI's will look similar to:

```
http://placeholder.catalogue.ceh.ac.uk/qc/meta/identity/maintenance
http://placeholder.catalogue.ceh.ac.uk/qc/meta/value/battery/single/min
```

### QC Thresholds

There are a large number of QC thresholds within the registry, some of which are static (such as sensor physical recording abilities), some of which change over time (such as seasonal thresholds recalculated each year), and some of which are defined for every hour, half-day, day, or month within a year.  These are detailed below.

#### Null values

Null values in isolation are not necessarily a very bad thing, however when a (relatively) large amount are generated over a temporal period, or when there are a large amount of consecutive null values, this can be a sign of a problem and should be looked into.  In the example below each procedure has four null thresholds described as:

```
feat::proc::obsprop::thresholds::null::aggregate::1h 4
feat::proc::obsprop::thresholds::null::aggregate::12h 16
feat::proc::obsprop::thresholds::null::aggregate::24h 20

feat::proc::obsprop::thresholds::null::consecutive 3
```

This shows that for every hour, twelve hour, and twenty four hour period, there can be four, sixteen, or twenty null values respectively.  The maximum number of consecutive null values allowed before a event is triggered is three.

#### Delta, Range, and Sigma Checks

The delta (acceleration), range (min/max), and sigma (variance) thresholds provide an upper and lower bound for the range and sigma  checks, and an upper bound for the delta check.  Within the delta check there is both a step and spike test: the step test looks at two consecutive values, while the spike test looks at three consecutive values.

For all variations on the delta, range, and sigma checks, there can be a number of different methods used to generate the threshold values, and there can be different threshold values per generation method.

It also does not matter for each of these checks whether there is only a minimum or maximum threshold, it will function regardless.

##### Range

To illustrate, imagine that for the range min and max check there are three generation methods:

* Method A (hardware limits)
* Method B (data derived seasonal limits)
* Method C (statistical based forecasting)

Method A is an example of a minimum and maximum range threshold that does not change over time.  Method B is an example that may have different minimum and maximum thresholds depending on the month.  Method C could have different methods per month, per day, or even per hour.  For a given sensor, these would be initially defined as:

```
feat::proc::obsprop::thresholds::range methoda::methodb::methodc
```
This tells the processor that there are three methods to be used when checking an observation's minimum and maximum range.  The entries below tell the processor that Method A has a single minimum and maximum value to be used in comparisons.  Method B however has a different set of thresholds for each month of the year, while Method C has a different set of thresholds for each hour of every day.

```
feat::proc::obsprop::thresholds::range::methoda single
feat::proc::obsprop::thresholds::range::methodb month
feat::proc::obsprop::thresholds::range::methodc hour
```

Now that the processor knows what tests exist, and how their minimum and maximum values are allocated, it can then lookup the threshold values for comparison using keys of the following nature:

```
feat::proc::obsprop::thresholds::range::methoda::min 950
feat::proc::obsprop::thresholds::range::methoda::max 1150

feat::proc::obsprop::thresholds::range::methodb::min::2016-01 900
feat::proc::obsprop::thresholds::range::methodb::max::2016-01 1050
feat::proc::obsprop::thresholds::range::methodb::min::2016-02 925
feat::proc::obsprop::thresholds::range::methodb::max::2016-02 1075
feat::proc::obsprop::thresholds::range::methodb::min::2016-12 850
feat::proc::obsprop::thresholds::range::methodb::max::2016-12 1000

feat::proc::obsprop::thresholds::range::methodc::min::2016-01-01T00 900
feat::proc::obsprop::thresholds::range::methodc::max::2016-01-01T00 1000
feat::proc::obsprop::thresholds::range::methodc::min::2016-01-01T01 910
feat::proc::obsprop::thresholds::range::methodc::max::2016-01-01T01 1010
feat::proc::obsprop::thresholds::range::methodc::min::2016-01-01T23 920
feat::proc::obsprop::thresholds::range::methodc::max::2016-01-01T23 1020
```
When there is a single set of thresholds, they are accessed using `method::min` or `::max`.  When there is a time resolution, the string used to lookup includes the timestamp up to the desired resolution.  The supported resolutions are:

* single: only one set of thresholds
* hour: thresholds per hour, processor must round to nearest hour
* day: thresholds per day, processor must round to nearest 12:00:00 
* month: thresholds per month, processor must round to current month

##### Sigma

The above layout is similar for sigma in that there can be different threshold generators, however, variance is calculated over a time-window rather than a single observation.  Due to this, there is the time resolution, but the time-window duration to also take into account.  Within the processing setup we use one hour, twelve hour, and twenty four hour windows.  In all other aspects it is the same as with `range`, for example:

```
feat::proc::obsprop::thresholds::sigma methoda::methodb

feat::proc::obsprop::thresholds::sigma::methoda single
feat::proc::obsprop::thresholds::sigma::methodb month

feat::proc::obsprop::thresholds::sigma::1h::methoda::min 50
feat::proc::obsprop::thresholds::sigma::12h::methoda::min 75
feat::proc::obsprop::thresholds::sigma::24h::methoda::min 100

feat::proc::obsprop::thresholds::sigma::1h::methoda::max 75
feat::proc::obsprop::thresholds::sigma::12h::methoda::max 100
feat::proc::obsprop::thresholds::sigma::24h::methoda::max 1025

feat::proc::obsprop::thresholds::sigma::1h::methodb::min::2016-01 75
feat::proc::obsprop::thresholds::sigma::1h::methodb::max::2016-01 150

```

##### Delta

Delta is similar to `range`, except that it has two subcategories of `step` and `spike`.  These are postfixed as an element of the key following the `delta` element, e.g.:

```
feat::proc::obsprop::thresholds::delta::step::methoda single

feat::proc::obsprop::thresholds::delta::step::methoda::min 20
feat::proc::obsprop::thresholds::delta::spike::methoda::max 30
```